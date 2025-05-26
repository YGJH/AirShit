package AirShit;

import AirShit.ui.LogPanel;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class Receiver {
    private final ServerSocket serverSocket; // This is the ServerSocket passed from FileReceiver

    public Receiver(ServerSocket ss) {
        this.serverSocket = ss;
    }    public boolean start(String outputFile,
                         long fileLength, // Expected total file length from handshake
                         int threadCount,  // Negotiated thread count
                         TransferCallback cb) throws InterruptedException {
        
        // 限制執行緒數量，避免過多併發
        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() * 4);
        threadCount = Math.min(Math.max(1, threadCount), maxThreads);
        
        LogPanel.log("Receiver.start(): 開始接收檔案 " + outputFile + 
                    " (預期大小: " + fileLength + " bytes, 執行緒數: " + threadCount + ")");

        // 如果檔案大小為 0，直接完成
        if (fileLength == 0) {
            if (cb != null) {
                cb.onStart(0);
                cb.onComplete();
            }
            return true;
        }

        AtomicLong totalBytesReceived = new AtomicLong(0);
        
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            // 預先分配檔案空間
            raf.setLength(fileLength);
            
            if (cb != null) {
                cb.onStart(fileLength);
            }
            
            // 使用 Virtual Thread Executor
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                
                // 建立多個 ReceiverWorker
                for (int i = 0; i < threadCount; i++) {
                    Future<?> future = executor.submit(() -> {
                        try {
                            // 等待連接
                            Socket socket = serverSocket.accept();
                            LogPanel.log("ReceiverWorker: 接受新連接 " + socket.getRemoteSocketAddress());
                            
                            ReceiverWorker worker = new ReceiverWorker(socket, raf, cb, totalBytesReceived, fileLength);
                            worker.run();
                            
                        } catch (IOException e) {
                            LogPanel.log("ReceiverWorker: 接受連接時發生錯誤: " + e.getMessage());
                            if (cb != null) {
                                cb.onError(e);
                            }
                        }
                    });
                    futures.add(future);
                }
                
                // 等待所有 worker 完成
                boolean allCompleted = true;
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        LogPanel.log("ReceiverWorker 執行錯誤: " + e.getMessage());
                        allCompleted = false;
                    }
                }
                
                // 檢查接收是否完整
                long totalReceived = totalBytesReceived.get();
                LogPanel.log("Receiver: 總共接收 " + totalReceived + " bytes，預期 " + fileLength + " bytes");
                
                if (totalReceived == fileLength && allCompleted && cb != null) {
                    cb.onComplete();
                }
                
                return totalReceived == fileLength && allCompleted;
            }
        } catch (IOException e) {
            LogPanel.log("Receiver: 檔案操作錯誤: " + e.getMessage());
            if (cb != null) {
                cb.onError(e);
            }
            return false;
        }
    }

    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf; // Can be null if expectedTotalFileLength is 0
        private final TransferCallback callback;
        private final AtomicLong totalBytesActuallyReceivedOverall;
        private final long expectedTotalFileLength;
        private final FileChannel fileChannel; // Added FileChannel


        public ReceiverWorker(Socket dataSocket, RandomAccessFile raf, TransferCallback callback, AtomicLong totalReceived, long expectedTotalFileLength) {
            this.dataSocket = dataSocket;
            this.raf = raf;
            this.callback = callback;
            this.totalBytesActuallyReceivedOverall = totalReceived;
            this.expectedTotalFileLength = expectedTotalFileLength;
            this.fileChannel = (this.raf != null) ? this.raf.getChannel() : null; // Get FileChannel from raf
        }        @Override
        public void run() {
            try {
                // 設定 socket 選項以提高效能
                dataSocket.setTcpNoDelay(true);
                dataSocket.setReceiveBufferSize(2 * 1024 * 1024); // 2MB buffer
                dataSocket.setSoTimeout(30000); // 30 秒超時
                
                // 使用 NIO 進行高效率資料傳輸
                try (ReadableByteChannel socketChannel = Channels.newChannel(dataSocket.getInputStream())) {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024); // 1MB direct buffer
                    long totalBytesRead = 0;
                    
                    // 讀取 chunk metadata: offset (8 bytes) + length (8 bytes)
                    ByteBuffer metaBuffer = ByteBuffer.allocate(16);
                    while (metaBuffer.hasRemaining()) {
                        int bytesRead = socketChannel.read(metaBuffer);
                        if (bytesRead == -1) {
                            LogPanel.log("ReceiverWorker: 連接意外關閉");
                            return;
                        }
                    }
                    
                    metaBuffer.flip();
                    long chunkOffset = metaBuffer.getLong();
                    long chunkLength = metaBuffer.getLong();
                    
                    LogPanel.log("ReceiverWorker: 接收 chunk offset=" + chunkOffset + ", length=" + chunkLength);
                    
                    // 移動到檔案的正確位置
                    if (fileChannel != null) {
                        fileChannel.position(chunkOffset);
                    }
                    
                    // 使用 zero copy transferFrom 直接從 socket 寫入檔案
                    long remainingBytes = chunkLength;
                    while (remainingBytes > 0) {
                        buffer.clear();
                        buffer.limit((int) Math.min(buffer.capacity(), remainingBytes));
                        
                        int bytesRead = socketChannel.read(buffer);
                        if (bytesRead == -1) {
                            LogPanel.log("ReceiverWorker: 資料傳輸中斷");
                            break;
                        }
                        
                        if (bytesRead > 0) {
                            buffer.flip();
                            
                            // 寫入檔案
                            if (fileChannel != null) {
                                while (buffer.hasRemaining()) {
                                    fileChannel.write(buffer);
                                }
                            }
                            
                            totalBytesRead += bytesRead;
                            remainingBytes -= bytesRead;
                            
                            // 更新總進度
                            totalBytesActuallyReceivedOverall.addAndGet(bytesRead);
                            
                            // 報告進度
                            if (callback != null) {
                                callback.onProgress(bytesRead);
                            }
                        }
                    }
                    
                    // 強制寫入磁碟
                    if (fileChannel != null) {
                        fileChannel.force(false);
                    }
                    
                    LogPanel.log("ReceiverWorker 完成，接收了 " + totalBytesRead + " bytes (chunk offset=" + chunkOffset + ")");
                    
                } catch (IOException e) {
                    LogPanel.log("ReceiverWorker: 資料傳輸錯誤: " + e.getMessage());
                    if (callback != null) {
                        callback.onError(e);
                    }
                }
                
            } catch (Exception e) {
                LogPanel.log("ReceiverWorker: 設定 socket 時發生錯誤: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                try {
                    dataSocket.close();
                } catch (IOException e) {
                    LogPanel.log("ReceiverWorker: 關閉 socket 時發生錯誤: " + e.getMessage());
                }
            }
        }
    }
}
