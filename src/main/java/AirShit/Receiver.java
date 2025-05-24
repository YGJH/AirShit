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
                    // 設定較長的 SO_TIMEOUT，避免永久阻塞
                    dataSocket.setSoTimeout(60000); // 60 seconds read timeout
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
            
            // 等待所有 ReceiverWorker 任務完成
            boolean allWorkersCompleted = true;
            executor.shutdown();
            if (!executor.awaitTermination(24, TimeUnit.HOURS)) {
                executor.shutdownNow();
                LogPanel.log("Executor shutdown forced after timeout");
                allWorkersCompleted = false;
                success = false;
            }
            
            // 檢查每個任務的結果
            for (int i = 0; i < futures.size(); i++) {
                Future<?> future = futures.get(i);
                try {
                    future.get();  // 如果任務拋出異常，這將重新拋出它
                } catch (ExecutionException e) {
                    LogPanel.log("Worker " + i + " failed with exception: " + e.getCause().getMessage());
                    success = false;
                }
            }
            
            // 所有資料都已接收，確認總大小
            final long totalReceived = totalBytesActuallyReceivedOverall.get();
            LogPanel.log("Total bytes received: " + totalReceived + " of expected " + fileLength);
            
            if (fileLength > 0 && totalReceived != fileLength) {
                LogPanel.log("Warning: Received bytes (" + totalReceived + ") != expected file length (" + fileLength + ")");
                // 根據實際差距決定是否視為失敗 - 小誤差可能可以接受
                if (Math.abs(totalReceived - fileLength) > fileLength * 0.01) { // 1% 誤差容忍
                    success = false;
                }
            }
            
            // 通知完成
            if (success && cb != null) cb.onComplete();
            
        } catch (IOException e) {
            LogPanel.log("Error during receive: " + e.getMessage());
            if (cb != null) cb.onError(e);
            success = false;
        } finally {
            // 在所有工作完成後，確保所有資料通訊端都被關閉
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
        private final RandomAccessFile raf;
        private final TransferCallback callback;
        private final AtomicLong totalBytesActuallyReceivedOverall;
        private final FileChannel fileChannel;

        public ReceiverWorker(Socket dataSocket, RandomAccessFile raf, TransferCallback callback, AtomicLong totalReceived, long expectedTotalFileLength) {
            this.dataSocket = dataSocket;
            this.raf = raf;
            this.callback = callback;
            this.totalBytesActuallyReceivedOverall = totalReceived;
            this.fileChannel = (this.raf != null) ? this.raf.getChannel() : null;
        }

        @Override
        public void run() {
            LogPanel.log("ReceiverWorker started for socket: " + dataSocket);
            long totalBytesReceived = 0;
            
            try {
                // 使用 NIO 來加速檔案接收與寫入
                ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024); // 使用 64KB 的直接緩衝區
                
                // 從 Socket 得到輸入流，並轉換為 NIO 通道
                ReadableByteChannel socketChannel = Channels.newChannel(dataSocket.getInputStream());
                
                int bytesRead;
                boolean orderlyClosure = false;
                
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
                
                // 如果 bytesRead == 0，這表示對端正常關閉了流（調用了 socketChannel.shutdownOutput()）
                if (bytesRead == 0) {
                    orderlyClosure = true;
                    LogPanel.log("ReceiverWorker detected orderly closure from sender");
                }
                
                // 接收完成後記錄
                LogPanel.log("ReceiverWorker completed, received " + totalBytesReceived + " bytes" + 
                            (orderlyClosure ? " (orderly closure)" : ""));
                
            } catch (IOException e) {
                LogPanel.log("ReceiverWorker error on socket: " + dataSocket + ", error: " + e.getMessage());
                if (callback != null) {
                    try {
                        callback.onError(e);
                    } catch (Exception cbEx) {
                        LogPanel.log("Exception in ReceiverWorker's onError callback: " + cbEx.getMessage());
                    }
                }
            } 
            // 重要：不在 finally 中關閉 socket，由外層統一處理
        }
    }
}
