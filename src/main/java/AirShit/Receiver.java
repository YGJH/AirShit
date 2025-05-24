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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors; // Import Virtual Threads executor
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
        
        final List<Socket> dataSockets = new ArrayList<>(threadCount);
        final AtomicLong totalBytesActuallyReceivedOverall = new AtomicLong(0);
        boolean success = true;  // Track success for return value
        
        // 設定 timeout 以防止無限等待
        try {
            serverSocket.setSoTimeout(30000);  // 30 seconds timeout
        } catch (IOException e) {
            LogPanel.log("Unable to set timeout on server socket: " + e.getMessage());
            return false;
        }
        
        // 使用 Virtual Threads 執行緒池
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>(threadCount);
        
        try (RandomAccessFile raf = (fileLength > 0) ? new RandomAccessFile(out, "rw") : null) {
            // 如果有指定檔案大小，先預設檔案大小以便隨機寫入
            if (raf != null && fileLength > 0) {
                try {
                    raf.setLength(fileLength);
                    LogPanel.log("Pre-allocated file size: " + fileLength + " bytes");
                } catch (IOException e) {
                    LogPanel.log("Failed to set file length: " + e.getMessage());
                }
            }
            
            // 如果提供了回呼，通知開始
            if (cb != null) cb.onStart(fileLength);
            
            // 接受所有的資料連接
            for (int i = 0; i < threadCount; i++) {
                try {
                    Socket dataSocket = serverSocket.accept();
                    dataSockets.add(dataSocket);
                    
                    // 提交任務到執行緒池，使用 Virtual Threads
                    futures.add(executor.submit(new ReceiverWorker(dataSocket, raf, cb, totalBytesActuallyReceivedOverall, fileLength)));
                } catch (SocketTimeoutException e) {
                    LogPanel.log("Socket accept timed out after: " + i + " connections");
                    success = false;
                    break;
                } catch (IOException e) {
                    LogPanel.log("Error accepting connection: " + e.getMessage());
                    success = false;
                    break;
                }
            }
            
            // 關閉新連接的接受
            try {
                serverSocket.setSoTimeout(1);  // 幾乎馬上超時
            } catch (IOException e) {
                LogPanel.log("Error setting short timeout: " + e.getMessage());
            }
            
            // 關閉執行緒池並等待所有任務完成
            executor.shutdown();
            if (!executor.awaitTermination(24, TimeUnit.HOURS)) {
                executor.shutdownNow();
                LogPanel.log("Executor shutdown forced after timeout");
                success = false;
            }
            
            // 檢查每個任務的結果
            for (Future<?> future : futures) {
                try {
                    future.get();  // 如果任務拋出異常，這將重新拋出它
                } catch (ExecutionException e) {
                    LogPanel.log("Task failed with exception: " + e.getCause().getMessage());
                    success = false;
                }
            }
            
            // 所有資料都已接收，確認總大小
            final long totalReceived = totalBytesActuallyReceivedOverall.get();
            if (fileLength > 0 && totalReceived != fileLength) {
                LogPanel.log("Warning: Received bytes (" + totalReceived + ") != expected file length (" + fileLength + ")");
                success = false;
            }
            
            // 通知完成
            if (success && cb != null) cb.onComplete();
            
        } catch (IOException e) {
            LogPanel.log("Error during receive: " + e.getMessage());
            if (cb != null) cb.onError(e);
            success = false;
        } finally {
            // 確保所有資料通訊端都被關閉
            for (Socket s : dataSockets) {
                try {
                    if (s != null && !s.isClosed()) s.close();
                } catch (IOException e) {
                    LogPanel.log("Error closing data socket: " + e.getMessage());
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
        private final RandomAccessFile raf; // Can be null if expectedTotalFileLength is 0
        private final TransferCallback callback;
        private final AtomicLong totalBytesActuallyReceivedOverall;        private final FileChannel fileChannel; // Added FileChannel


        public ReceiverWorker(Socket dataSocket, RandomAccessFile raf, TransferCallback callback, AtomicLong totalReceived, long expectedTotalFileLength) {
            this.dataSocket = dataSocket;
            this.raf = raf;
            this.callback = callback;
            this.totalBytesActuallyReceivedOverall = totalReceived;
            this.fileChannel = (this.raf != null) ? this.raf.getChannel() : null; // Get FileChannel from raf
        }

        @Override
        public void run() {
            LogPanel.log("ReceiverWorker started");
            long totalBytesReceived = 0;
            
            try {
                // 使用 NIO 來加速檔案接收與寫入
                ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024); // 使用 64KB 的直接緩衝區
                
                // 從 Socket 得到輸入流，並轉換為 NIO 通道
                ReadableByteChannel socketChannel = Channels.newChannel(dataSocket.getInputStream());
                
                int bytesRead;
                
                // 循環接收資料，直到沒有更多資料或連線關閉
                while ((bytesRead = socketChannel.read(buffer)) > 0) {
                    buffer.flip(); // 準備讀取緩衝區中的資料
                    
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
                
                // 接收完成後記錄
                LogPanel.log("ReceiverWorker completed, received " + totalBytesReceived + " bytes");
                
            } catch (IOException e) {
                LogPanel.log("ReceiverWorker error: " + e.getMessage());
                if (callback != null) {
                    try {
                        callback.onError(e);
                    } catch (Exception cbEx) {
                        LogPanel.log("Exception in ReceiverWorker's onError callback: " + cbEx.getMessage());
                    }
                }
            } finally {
                // 由外層 finally 關閉 Socket
            }
        }
    }
}
