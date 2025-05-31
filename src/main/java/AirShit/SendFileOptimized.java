package AirShit;

import AirShit.ui.LogPanel;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 超高效能檔案傳送器，使用 Zero Copy + Virtual Threads
 */
public class SendFileOptimized {
    private final String host;
    private final int port;
    private final File file;
    private final TransferCallback originalCallback;
    private int threadCount;
    private final AtomicBoolean errorReportedByWorker = new AtomicBoolean(false);

    public SendFileOptimized(String host, int port, File file, int threadCount, TransferCallback callback) {
        this.originalCallback = callback;
        this.host = host;
        this.port = port;
        this.file = file;
        // 限制最高併發數：以 CPU 核心數 * 4 為上限
        int maxThreads = Math.max(1, threadCount);
        this.threadCount = Math.min(Math.max(1, threadCount), maxThreads);
        // LogPanel.log("SendFileOptimized: 限制 threadCount=" + this.threadCount + " (原始=" + threadCount + ")");
    }

    private TransferCallback getWrappedCallback() {
        return new TransferCallback() {
            @Override
            public void onStart(long totalSize) {
                if (originalCallback != null) originalCallback.onStart(totalSize);
            }

            public void onStart(long totalSize , String name) {
                if (originalCallback != null) originalCallback.onStart(totalSize);
            }

            @Override
            public void onProgress(long bytes) {
                if (originalCallback != null) originalCallback.onProgress(bytes);
            }


            @Override
            public void onComplete(String name) {
                // 不直接使用
            }

            @Override
            public void onComplete() {
                // 由主邏輯呼叫
            }

            @Override
            public void onError(Exception e) {
                errorReportedByWorker.set(true);
                if (originalCallback != null) originalCallback.onError(e);
            }
        };
    }

    public void start() throws IOException, InterruptedException {
        long fileLength = file.length();
        
        if (fileLength == 0) {
            if (originalCallback != null) {
                originalCallback.onStart(0 , file.getName());
                originalCallback.onComplete();
            }
            return;
        }

        LogPanel.log("SendFileOptimized: 開始傳送檔案 " + file.getName() + 
                    " (大小: " + fileLength + " bytes, 執行緒數: " + threadCount + ")");

        // 建立檔案區塊佇列
        ConcurrentLinkedQueue<ChunkInfo> chunkQueue = new ConcurrentLinkedQueue<>();
        populateChunkQueue(fileLength, chunkQueue);

        // 使用 Virtual Thread Executor
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
             FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            
            if (originalCallback != null) {
                originalCallback.onStart(fileLength , file.getName());
            }

            List<Future<?>> futures = new ArrayList<>();
            TransferCallback wrappedCallback = getWrappedCallback();

            for (int i = 0; i < threadCount; i++) {
                Future<?> future = executor.submit(() -> {
                    try (SocketChannel socketChannel = SocketChannel.open()) {
                        // 連接到接收端
                        socketChannel.connect(new InetSocketAddress(host, port));
                        
                        // 設定高效能選項
                        socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        socketChannel.setOption(StandardSocketOptions.SO_SNDBUF, 2 * 1024 * 1024); // 2MB
                        socketChannel.configureBlocking(true);
                        
                        // 建立並執行 worker
                        SenderWorker worker = new SenderWorker(socketChannel, fileChannel, chunkQueue, wrappedCallback);
                        worker.run();
                        
                    } catch (IOException e) {
                        wrappedCallback.onError(e);
                    }
                });
                futures.add(future);
            }

            // 等待所有 worker 完成
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    LogPanel.log("SendFileOptimized Worker 錯誤: " + e.getCause());
                }
            }

            // 檢查是否有錯誤
            if (!errorReportedByWorker.get() && originalCallback != null) {
                originalCallback.onComplete();
            }
        }
    }

    private void populateChunkQueue(long fileLength, ConcurrentLinkedQueue<ChunkInfo> chunkQueue) {
        // 動態調整 chunk 大小
        long chunkSize = 1024 * 1024; // 1MB 基準
        
        // 根據檔案大小和執行緒數調整
        long minChunks = Math.max(threadCount * 3, 8);
        if (fileLength / chunkSize < minChunks && fileLength > 0) {
            chunkSize = Math.max(fileLength / minChunks, 64 * 1024); // 最小 64KB
        }
        
        // 對於大檔案，使用更大的 chunk
        if (fileLength > 100L * 1024 * 1024) { // 100MB+
            chunkSize = Math.min(chunkSize * 2, 4 * 1024 * 1024); // 最大 4MB
        }
        
        long offset = 0;
        while (offset < fileLength) {
            long length = Math.min(chunkSize, fileLength - offset);
            chunkQueue.offer(new ChunkInfo(offset, length));
            offset += length;
        }
        
        LogPanel.log("SendFileOptimized: 創建了 " + chunkQueue.size() + " 個 chunk，大小約 " + (chunkSize / 1024) + "KB");
    }

    private static class SenderWorker implements Runnable {
        private final SocketChannel socketChannel;
        private final FileChannel fileChannel;
        private final ConcurrentLinkedQueue<ChunkInfo> chunkQueue;
        private final TransferCallback callback;

        public SenderWorker(SocketChannel socketChannel, FileChannel fileChannel,
                          ConcurrentLinkedQueue<ChunkInfo> chunkQueue, TransferCallback callback) {
            this.socketChannel = socketChannel;
            this.fileChannel = fileChannel;
            this.chunkQueue = chunkQueue;
            this.callback = callback;
        }

        @Override
        public void run() {
            long totalBytesSent = 0;
            
            try {
                ChunkInfo chunk;
                while ((chunk = chunkQueue.poll()) != null) {
                    if (!sendChunk(chunk)) {
                        // 發送失敗，放回佇列重試
                        chunkQueue.offer(chunk);
                        Thread.sleep(50);
                        continue;
                    }
                    totalBytesSent += chunk.length;
                }
                
                // LogPanel.log("SenderWorker 完成，發送: " + totalBytesSent + " bytes");
                
            } catch (Exception e) {
                LogPanel.log("SenderWorker 錯誤: " + e.getMessage());
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                try {
                    socketChannel.close();
                } catch (IOException e) {
                    LogPanel.log("關閉 SocketChannel 錯誤: " + e.getMessage());
                }
            }
        }

        private boolean sendChunk(ChunkInfo chunk) {
            try {
                // 發送 chunk metadata (offset + length)
                ByteBuffer metaBuffer = ByteBuffer.allocate(16);
                metaBuffer.putLong(chunk.offset);
                metaBuffer.putLong(chunk.length);
                metaBuffer.flip();
                
                while (metaBuffer.hasRemaining()) {
                    socketChannel.write(metaBuffer);
                }
                
                // 使用 zero copy transferTo
                long bytesToSend = chunk.length;
                long offset = chunk.offset;
                
                while (bytesToSend > 0) {
                    long sent = fileChannel.transferTo(offset, bytesToSend, socketChannel);
                    if (sent == 0) {
                        Thread.sleep(1); // 避免忙碌等待
                        continue;
                    }
                    
                    bytesToSend -= sent;
                    offset += sent;
                    
                    // 報告進度
                    if (callback != null) {
                        callback.onProgress(sent);
                    }
                }
                
                return true;
                
            } catch (Exception e) {
                LogPanel.log("發送 chunk 失敗: " + e.getMessage());
                return false;
            }
        }
    }
}