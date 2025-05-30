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

/**
 * 超高效能檔案接收器，使用 Zero Copy + Virtual Threads
 */
public class ReceiverOptimized {
    private final ServerSocket serverSocket;

    public ReceiverOptimized(ServerSocket ss) {
        this.serverSocket = ss;
    }

    public boolean start(String outputFile,
                         long fileLength,
                         int threadCount,
                         TransferCallback cb) throws InterruptedException {
        
        // 限制執行緒數量，避免過多併發
        int maxThreads = Math.max(1, threadCount);
        threadCount = Math.min(Math.max(1, threadCount), maxThreads);
        
        LogPanel.log("ReceiverOptimized: 開始接收檔案 " + outputFile + 
                    " (預期大小: " + fileLength + " bytes, 執行緒數: " + threadCount + ")");

        if (fileLength == 0) {
            if (cb != null) {
                cb.onStart(0, outputFile);
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
                
                LogPanel.log("ReceiverOptimized: 準備接受 " + threadCount + " 個並發連接...");
                
                // 建立多個 ReceiverWorker
                for (int i = 0; i < threadCount; i++) {
                    final int workerIndex = i;
                    Future<?> future = executor.submit(() -> {
                        try {
                            LogPanel.log("ReceiverOptimized Worker " + workerIndex + ": 等待連接...");
                            
                            // 設定較長的超時時間
                            serverSocket.setSoTimeout(30000); // 30秒超時
                            
                            // 等待連接
                            Socket socket = serverSocket.accept();
                            LogPanel.log("ReceiverOptimized Worker " + workerIndex + ": 接受連接 " + socket.getRemoteSocketAddress());
                            
                            ReceiverWorker worker = new ReceiverWorker(socket, raf, cb, totalBytesReceived, fileLength);
                            worker.run();
                            
                        } catch (IOException e) {
                            LogPanel.log("ReceiverOptimized Worker " + workerIndex + ": 連接錯誤: " + e.getMessage());
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
                        LogPanel.log("ReceiverOptimized Worker 執行錯誤: " + e.getMessage());
                        allCompleted = false;
                    }
                }
                
                // 檢查接收是否完整
                long totalReceived = totalBytesReceived.get();
                LogPanel.log("ReceiverOptimized: 總共接收 " + totalReceived + " bytes，預期 " + fileLength + " bytes");
                
                if (totalReceived == fileLength && allCompleted && cb != null) {
                    cb.onComplete();
                }
                
                return totalReceived == fileLength && allCompleted;
            }
        } catch (IOException e) {
            LogPanel.log("ReceiverOptimized: 檔案操作錯誤: " + e.getMessage());
            if (cb != null) {
                cb.onError(e);
            }
            return false;
        }
    }

    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf;
        private final TransferCallback callback;
        private final AtomicLong totalBytesReceived;
        @SuppressWarnings("unused")
        private final long expectedFileLength;
        private final FileChannel fileChannel;

        public ReceiverWorker(Socket dataSocket, RandomAccessFile raf, TransferCallback callback, 
                            AtomicLong totalReceived, long expectedFileLength) {
            this.dataSocket = dataSocket;
            this.raf = raf;
            this.callback = callback;
            this.totalBytesReceived = totalReceived;
            this.expectedFileLength = expectedFileLength;
            this.fileChannel = (this.raf != null) ? this.raf.getChannel() : null;
        }

        @Override
        public void run() {
            long totalBytesRead = 0;
            
            try {
                // 設定高效能 socket 選項
                dataSocket.setTcpNoDelay(true);
                dataSocket.setReceiveBufferSize(4 * 1024 * 1024); // 4MB buffer
                dataSocket.setSoTimeout(60000); // 60 秒超時
                
                // 使用 NIO 進行高效資料傳輸
                try (ReadableByteChannel socketChannel = Channels.newChannel(dataSocket.getInputStream())) {
                    
                    while (true) {
                        // 讀取 chunk metadata: offset (8 bytes) + length (8 bytes)
                        ByteBuffer metaBuffer = ByteBuffer.allocate(16);
                        if (!readFully(socketChannel, metaBuffer)) {
                            break; // 連接關閉
                        }
                        
                        metaBuffer.flip();
                        long chunkOffset = metaBuffer.getLong();
                        long chunkLength = metaBuffer.getLong();
                        
                        // LogPanel.log("ReceiverOptimized Worker: 接收 chunk offset=" + chunkOffset + 
                        //            ", length=" + chunkLength);
                        
                        // 接收 chunk 資料
                        long bytesRead = receiveChunkData(socketChannel, chunkOffset, chunkLength);
                        totalBytesRead += bytesRead;
                        
                        // 更新總進度
                        totalBytesReceived.addAndGet(bytesRead);
                        
                        // 報告進度
                        if (callback != null) {
                            callback.onProgress(bytesRead);
                        }
                        
                        if (bytesRead < chunkLength) {
                            LogPanel.log("ReceiverOptimized Worker: 接收不完整，預期 " + chunkLength + 
                                       "，實際 " + bytesRead);
                            break;
                        }
                    }
                    
                } catch (IOException e) {
                    LogPanel.log("ReceiverOptimized Worker: 資料傳輸錯誤: " + e.getMessage());
                    if (callback != null) {
                        callback.onError(e);
                    }
                }
                
                // LogPanel.log("ReceiverOptimized Worker 完成，接收了 " + totalBytesRead + " bytes");
                
            } catch (Exception e) {
                LogPanel.log("ReceiverOptimized Worker: 錯誤: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                try {
                    dataSocket.close();
                } catch (IOException e) {
                    LogPanel.log("ReceiverOptimized Worker: 關閉 socket 錯誤: " + e.getMessage());
                }
            }
        }

        private boolean readFully(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
            while (buffer.hasRemaining()) {
                int bytesRead = channel.read(buffer);
                if (bytesRead == -1) {
                    return false; // 連接關閉
                }
                if (bytesRead == 0) {
                    Thread.yield(); // 讓出 CPU
                }
            }
            return true;
        }

        private long receiveChunkData(ReadableByteChannel socketChannel, long chunkOffset, long chunkLength) 
                throws IOException {
            
            if (fileChannel != null) {
                fileChannel.position(chunkOffset);
            }
            
            // 使用大 buffer 提高效能
            ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024); // 1MB direct buffer
            long remainingBytes = chunkLength;
            long totalRead = 0;
            
            while (remainingBytes > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remainingBytes));
                
                int bytesRead = socketChannel.read(buffer);
                if (bytesRead == -1) {
                    break; // 連接關閉
                }
                
                if (bytesRead > 0) {
                    buffer.flip();
                    
                    // 寫入檔案 (使用 zero copy 概念)
                    if (fileChannel != null) {
                        while (buffer.hasRemaining()) {
                            fileChannel.write(buffer);
                        }
                    }
                    
                    totalRead += bytesRead;
                    remainingBytes -= bytesRead;
                } else {
                    Thread.yield(); // 沒有資料時讓出 CPU
                }
            }
            
            // 強制寫入磁碟 (每個 chunk 完成後)
            if (fileChannel != null) {
                fileChannel.force(false);
            }
            
            return totalRead;
        }
    }
}
