package AirShit;

import AirShit.ui.LogPanel;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Receiver {
    private final ServerSocket serverSocket; // This is the ServerSocket passed from FileReceiver

    public Receiver(ServerSocket ss) {
        this.serverSocket = ss;
    }

    public boolean start(String outputFile,
                         long fileLength, // Expected total file length from handshake
                         int threadCount,  // Negotiated thread count
                         TransferCallback cb) throws InterruptedException {
        LogPanel.log("Receiver starting with " + threadCount + " threads (Virtual Threads) for " + outputFile + " (" + fileLength + " bytes)");
        
        // 建立輸出檔案
        File out = new File(outputFile);
        if (out.exists()) {
            LogPanel.log("File exists, deleting before receive: " + out.getAbsolutePath());
            if (!out.delete()) {
                LogPanel.log("Failed to delete existing file, will try to overwrite");
            }
        }
        
        // 確保目錄存在
        File parentDir = out.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                LogPanel.log("Failed to create parent directories for: " + out.getAbsolutePath());
                if (cb != null) cb.onError(new IOException("Failed to create parent directories"));
                return false;
            }
        }
        
        final List<Socket> dataSockets = new ArrayList<>();
        final Map<Integer, Socket> workerSockets = new HashMap<>(); // 用於存儲工作執行緒索引與 Socket 的映射
        final AtomicLong totalBytesActuallyReceivedOverall = new AtomicLong(0);
        boolean success = true;  // Track success for return value
        
        // 設定 timeout 以防止無限等待
        try {
            serverSocket.setSoTimeout(30000);  // 30 seconds timeout
        } catch (IOException e) {
            LogPanel.log("Unable to set timeout on server socket: " + e.getMessage());
            if (cb != null) cb.onError(e);
            return false;
        }
        
        // 使用 Virtual Threads 執行緒池
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();
        
        try (RandomAccessFile raf = (fileLength > 0) ? new RandomAccessFile(out, "rw") : null) {
            // 如果有指定檔案大小，先預設檔案大小以便隨機寫入
            if (raf != null && fileLength > 0) {
                try {
                    raf.setLength(fileLength);
                    LogPanel.log("Pre-allocated file size: " + fileLength + " bytes");
                } catch (IOException e) {
                    LogPanel.log("Failed to set file length: " + e.getMessage());
                    if (cb != null) cb.onError(e);
                    return false;
                }
            }
            
            // 如果提供了回呼，通知開始
            if (cb != null) cb.onStart(fileLength);
            
            // 首先等待設定連接
            LogPanel.log("等待發送方的設定連接...");
            Socket setupSocket = null;
            String fileIdString = null;
            int expectedWorkers = 0;
            
            try {
                setupSocket = serverSocket.accept();
                setupSocket.setSoTimeout(10000); // 10秒超時
                
                // 讀取設定訊息
                InputStream setupIn = setupSocket.getInputStream();
                ByteBuffer setupBuffer = ByteBuffer.allocate(256);
                byte[] setupBytes = new byte[256];
                int bytesRead = setupIn.read(setupBytes);
                
                if (bytesRead > 0) {
                    String setupMessage = new String(setupBytes, 0, bytesRead);
                    LogPanel.log("收到設定訊息: " + setupMessage);
                    
                    String[] parts = setupMessage.split("\\|");
                    if (parts.length >= 3 && "SETUP".equals(parts[0])) {
                        expectedWorkers = Integer.parseInt(parts[1]);
                        fileIdString = parts[2];
                        LogPanel.log("準備接收 " + expectedWorkers + " 個工作執行緒連接，檔案ID: " + fileIdString);
                        
                        // 發送就緒確認
                        setupSocket.getOutputStream().write("READY".getBytes());
                        setupSocket.getOutputStream().flush();
                    } else {
                        throw new IOException("無效的設定訊息格式");
                    }
                } else {
                    throw new IOException("讀取設定訊息時發生錯誤");
                }
            } catch (IOException e) {
                LogPanel.log("處理設定連接時發生錯誤: " + e.getMessage());
                if (cb != null) cb.onError(e);
                if (setupSocket != null && !setupSocket.isClosed()) {
                    try {
                        setupSocket.close();
                    } catch (IOException ex) {
                        LogPanel.log("關閉設定 socket 時出錯: " + ex.getMessage());
                    }
                }
                return false;
            }
            
            // 接受所有的資料連接
            for (int i = 0; i < expectedWorkers; i++) {
                try {
                    Socket dataSocket = serverSocket.accept();
                    dataSocket.setSoTimeout(15000); // 15秒讀取超時
                    
                    // 讀取工作執行緒識別訊息
                    InputStream dataIn = dataSocket.getInputStream();
                    byte[] idBytes = new byte[256];
                    int bytesRead = dataIn.read(idBytes);
                    
                    if (bytesRead > 0) {
                        String idMessage = new String(idBytes, 0, bytesRead);
                        LogPanel.log("收到工作執行緒訊息: " + idMessage);
                        
                        String[] parts = idMessage.split("\\|");
                        if (parts.length >= 3 && "WORKER".equals(parts[0])) {
                            int workerIndex = Integer.parseInt(parts[1]);
                            String receivedFileId = parts[2];
                            
                            if (fileIdString.equals(receivedFileId)) {
                                // 記錄此 Socket 與工作執行緒索引的關係
                                workerSockets.put(workerIndex, dataSocket);
                                dataSockets.add(dataSocket);
                                
                                // 發送確認
                                dataSocket.getOutputStream().write("ACK".getBytes());
                                dataSocket.getOutputStream().flush();
                                
                                LogPanel.log("已確認工作執行緒 " + workerIndex + " 的連接");
                            } else {
                                throw new IOException("檔案ID不匹配: 預期 " + fileIdString + ", 收到 " + receivedFileId);
                            }
                        } else {
                            throw new IOException("無效的工作執行緒訊息格式");
                        }
                    } else {
                        throw new IOException("讀取工作執行緒識別訊息時發生錯誤");
                    }
                } catch (SocketTimeoutException e) {
                    LogPanel.log("等待工作執行緒連接時超時，已接受 " + workerSockets.size() + "/" + expectedWorkers + " 個連接");
                    break;
                } catch (IOException e) {
                    LogPanel.log("接受工作執行緒連接時發生錯誤: " + e.getMessage());
                    // 繼續嘗試接受其他連接
                }
            }
            
            // 檢查是否至少接受了一個連接
            if (workerSockets.isEmpty()) {
                LogPanel.log("未能建立任何資料連接，傳輸失敗");
                if (cb != null) cb.onError(new IOException("未能建立任何資料連接"));
                return false;
            } else if (workerSockets.size() < expectedWorkers) {
                LogPanel.log("警告：只接受了 " + workerSockets.size() + "/" + expectedWorkers + " 個連接，繼續處理...");
            }
            
            // 提交所有接收工作者任務
            for (Map.Entry<Integer, Socket> entry : workerSockets.entrySet()) {
                int workerIndex = entry.getKey();
                Socket socket = entry.getValue();
                
                // 提交任務到執行緒池，使用 Virtual Threads
                futures.add(executor.submit(new ReceiverWorker(socket, raf, cb, totalBytesActuallyReceivedOverall, fileLength, workerIndex)));
            }
            
            // 等待所有 ReceiverWorker 任務完成
            boolean allWorkersCompleted = true;
            executor.shutdown();
            if (!executor.awaitTermination(24, TimeUnit.HOURS)) {
                executor.shutdownNow();
                LogPanel.log("執行緒池在超時後強制關閉");
                allWorkersCompleted = false;
                success = false;
            }
            
            // 檢查每個任務的結果
            int completedTasks = 0;
            for (int i = 0; i < futures.size(); i++) {
                Future<?> future = futures.get(i);
                try {
                    future.get();  // 如果任務拋出異常，這將重新拋出它
                    completedTasks++;
                } catch (ExecutionException e) {
                    LogPanel.log("工作執行緒 " + i + " 失敗，異常: " + e.getCause().getMessage());
                    success = false;
                }
            }
            
            LogPanel.log("成功完成 " + completedTasks + "/" + futures.size() + " 個接收任務");
            
            // 所有資料都已接收，確認總大小
            final long totalReceived = totalBytesActuallyReceivedOverall.get();
            LogPanel.log("總接收位元組數: " + totalReceived + " / 預期: " + fileLength);
            
            if (fileLength > 0 && totalReceived != fileLength) {
                LogPanel.log("警告: 接收的位元組數 (" + totalReceived + ") 與預期的檔案大小 (" + fileLength + ") 不符");
                // 根據實際差距決定是否視為失敗 - 小誤差可能可以接受
                if (Math.abs(totalReceived - fileLength) > fileLength * 0.01) { // 1% 誤差容忍
                    success = false;
                }
            }
            
            // 如果文件成功接收，設置正確的文件大小
            if (success && raf != null) {
                try {
                    raf.setLength(totalReceived);
                    LogPanel.log("調整文件大小為實際接收大小: " + totalReceived + " 位元組");
                } catch (IOException e) {
                    LogPanel.log("調整最終文件大小時出錯: " + e.getMessage());
                }
            }
            
            // 通知完成
            if (success && cb != null) cb.onComplete();
            
        } catch (IOException e) {
            LogPanel.log("接收過程中發生錯誤: " + e.getMessage());
            if (cb != null) cb.onError(e);
            success = false;
        } finally {
            // 確保所有資料通訊端都被關閉
            for (Socket s : dataSockets) {
                try {
                    if (s != null && !s.isClosed()) s.close();
                } catch (IOException e) {
                    LogPanel.log("關閉資料 socket 時出錯: " + e.getMessage());
                }
            }
            
            // 確保執行緒池已關閉
            if (!executor.isShutdown()) {
                executor.shutdownNow();
            }
        }
        
        return success;
    }

    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf;
        private final TransferCallback callback;
        private final AtomicLong totalBytesActuallyReceivedOverall;
        private final FileChannel fileChannel;
        private final int workerIndex;

        public ReceiverWorker(Socket dataSocket, RandomAccessFile raf, TransferCallback callback, 
                              AtomicLong totalReceived, long expectedTotalFileLength, int workerIndex) {
            this.dataSocket = dataSocket;
            this.raf = raf;
            this.callback = callback;
            this.totalBytesActuallyReceivedOverall = totalReceived;
            this.fileChannel = (this.raf != null) ? this.raf.getChannel() : null;
            this.workerIndex = workerIndex;
        }

        @Override
        public void run() {
            LogPanel.log("ReceiverWorker " + workerIndex + " 開始處理來自 " + dataSocket.getRemoteSocketAddress() + " 的連接");
            long totalBytesReceived = 0;
            
            try {
                // 使用 NIO 來加速檔案接收與寫入
                ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024); // 使用 64KB 的直接緩衝區
                
                // 從 Socket 得到輸入流，並轉換為 NIO 通道
                SocketChannel socketChannel = ((Socket)dataSocket).getChannel();
                if (socketChannel == null) {
                    // 如果不是 SocketChannel，則使用傳統方式
                    ReadableByteChannel socketReadChannel = Channels.newChannel(dataSocket.getInputStream());
                    
                    int bytesRead;
                    boolean receivedEOF = false;
                    
                    // 循環接收資料，直到沒有更多資料或連線關閉
                    while ((bytesRead = socketReadChannel.read(buffer)) > 0) {
                        buffer.flip(); // 準備讀取緩衝區中的資料
                        
                        // 檢查是否接收到 EOF 訊號
                        if (buffer.remaining() >= 3) {
                            byte[] possibleEOF = new byte[3];
                            // 標記當前位置
                            buffer.mark();
                            buffer.get(possibleEOF, 0, 3);
                            buffer.reset(); // 返回到標記位置
                            
                            String eofCheck = new String(possibleEOF);
                            if ("EOF".equals(eofCheck)) {
                                LogPanel.log("ReceiverWorker " + workerIndex + " 接收到 EOF 訊號");
                                
                                // 回覆確認
                                dataSocket.getOutputStream().write("CLOSE_ACK".getBytes());
                                dataSocket.getOutputStream().flush();
                                
                                receivedEOF = true;
                                break;
                            }
                        }
                        
                        // 取得要寫入的位置（基於當前總接收大小）
                        long writePosition = totalBytesActuallyReceivedOverall.getAndAdd(bytesRead);
                        
                        // 使用 FileChannel 寫入檔案，如果 fileChannel 不為 null
                        if (fileChannel != null) {
                            // 循環直到所有資料都寫入，應對大型緩衝區可能需要多次寫入的情況
                            int bytesToWrite = bytesRead;
                            while (bytesToWrite > 0) {
                                int bytesWritten = fileChannel.write(buffer, writePosition + (bytesRead - bytesToWrite));
                                bytesToWrite -= bytesWritten;
                            }
                        }
                        
                        // 更新總接收大小
                        totalBytesReceived += bytesRead;
                        
                        // 回報進度
                        if (callback != null) {
                            callback.onProgress(bytesRead);
                        }
                        
                        // 清空緩衝區以準備下一次讀取
                        buffer.clear();
                    }
                    
                    // 如果沒有收到 EOF 但 bytesRead <= 0，這可能表示連接已關閉
                    if (!receivedEOF && bytesRead <= 0) {
                        LogPanel.log("ReceiverWorker " + workerIndex + " 檢測到發送方關閉連接，但未收到 EOF 訊號");
                    }
                } else {
                    // 使用 SocketChannel 直接讀取
                    int bytesRead;
                    boolean receivedEOF = false;
                    
                    while ((bytesRead = socketChannel.read(buffer)) > 0) {
                        buffer.flip();
                        
                        // 檢查 EOF 訊號
                        if (buffer.remaining() >= 3) {
                            byte[] possibleEOF = new byte[3];
                            buffer.mark();
                            buffer.get(possibleEOF, 0, 3);
                            buffer.reset();
                            
                            String eofCheck = new String(possibleEOF);
                            if ("EOF".equals(eofCheck)) {
                                LogPanel.log("ReceiverWorker " + workerIndex + " 接收到 EOF 訊號");
                                
                                // 回覆確認
                                ByteBuffer ackBuffer = ByteBuffer.wrap("CLOSE_ACK".getBytes());
                                while (ackBuffer.hasRemaining()) {
                                    socketChannel.write(ackBuffer);
                                }
                                
                                receivedEOF = true;
                                break;
                            }
                        }
                        
                        // 寫入檔案
                        long writePosition = totalBytesActuallyReceivedOverall.getAndAdd(bytesRead);
                        
                        if (fileChannel != null) {
                            int bytesToWrite = bytesRead;
                            while (bytesToWrite > 0) {
                                int bytesWritten = fileChannel.write(buffer, writePosition + (bytesRead - bytesToWrite));
                                bytesToWrite -= bytesWritten;
                            }
                        }
                        
                        totalBytesReceived += bytesRead;
                        
                        if (callback != null) {
                            callback.onProgress(bytesRead);
                        }
                        
                        buffer.clear();
                    }
                    
                    if (!receivedEOF && bytesRead <= 0) {
                        LogPanel.log("ReceiverWorker " + workerIndex + " 檢測到發送方關閉連接，但未收到 EOF 訊號");
                    }
                }
                
                // 接收完成後記錄
                LogPanel.log("ReceiverWorker " + workerIndex + " 完成，接收了 " + totalBytesReceived + " 位元組");
                
            } catch (IOException e) {
                LogPanel.log("ReceiverWorker " + workerIndex + " 發生錯誤: " + e.getMessage());
                if (callback != null) {
                    try {
                        callback.onError(e);
                    } catch (Exception cbEx) {
                        LogPanel.log("ReceiverWorker " + workerIndex + " 的 onError 回呼中發生異常: " + cbEx.getMessage());
                    }
                }
            } finally {
                // 不主動關閉 socket，由外層統一關閉
            }
        }
    }
}
