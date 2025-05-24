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
import java.util.concurrent.Executors; // Import Virtual Threads executor
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * SendFile 類別負責將單一檔案透過多執行緒（如果適用）傳送給接收端。
 * 它將檔案分割成區塊 (chunks)，並為每個區塊（或一組區塊）啟動一個 SenderWorker。
 */
public class SendFile {
    private final String host; // 接收端主機名稱或 IP 位址
    private final int port;    // 接收端埠號
    private final File file;   // 要傳送的檔案
    private final TransferCallback originalCallback; // 原始的回呼介面，用於報告進度和錯誤
    private int threadCount;   // 用於傳輸的執行緒數量
    private final AtomicBoolean errorReportedByWorker = new AtomicBoolean(false); // 標記是否有任何 worker 報告錯誤

    /**
     * SendFile 的建構函式。
     * @param host 接收端主機。
     * @param port 接收端埠號。
     * @param file 要傳送的檔案。
     * @param threadCount 用於傳輸的執行緒數量。
     * @param callback 用於報告傳輸狀態的回呼。
     */
    public SendFile(String host, int port, File file, int threadCount, TransferCallback callback) {
        this.originalCallback = callback;
        this.host = host;
        this.port = port;
        this.file = file;
        // LogPanel.log("SendFile 建構函式: 收到 threadCount=" + threadCount);
        this.threadCount = Math.max(1, threadCount); // 確保至少有一個執行緒
        // LogPanel.log("SendFile 建構函式: 設定 SendFile.this.threadCount=" + this.threadCount);
    }

    /**
     * 獲取一個包裝後的回呼介面。
     * 這個包裝後的回呼會將錯誤狀態記錄到 errorReportedByWorker，
     * 並將事件轉發給原始的回呼。
     * @return 包裝後的 TransferCallback。
     */
    private TransferCallback getWrappedCallback() {
        return new TransferCallback() {
            @Override
            public void onStart(long totalSize) {
                if (originalCallback != null) originalCallback.onStart(totalSize);
            }

            @Override
            public void onProgress(long bytes) {
                if (originalCallback != null) originalCallback.onProgress(bytes);
            }
            @Override
            public void onComplete(String name) {
                // 此方法在此包裝中不直接使用，由 SendFile 主邏輯呼叫原始回呼的 onComplete()
            }
            @Override
            public void onComplete() {
                // 此方法由 SendFile 主邏輯在所有 worker 完成且無錯誤時呼叫。
            }

            @Override
            public void onError(Exception e) {
                // 如果任何 worker 呼叫 onError，我們標記它。
                // SendFile 主邏輯將決定是否呼叫原始回呼的 onComplete。
                errorReportedByWorker.set(true);
                if (originalCallback != null) originalCallback.onError(e);
            }
        };
    }    /**
     * 開始檔案傳輸過程。
     * 此方法會設定執行緒池，準備檔案區塊，並啟動 SenderWorker 執行緒來傳送資料。
     * @throws IOException 如果發生 I/O 錯誤，例如無法開啟檔案或網路錯誤。
     * @throws InterruptedException 如果執行緒在等待時被中斷。
     */
    public void start() throws IOException, InterruptedException {
        final long fileLength = file.length();
        LogPanel.log("開始傳送檔案: " + file.getName() + ", 大小: " + fileLength + " 位元組, 使用 " + threadCount + " 個執行緒 (Virtual Threads)");
        
        // 取得包裝後的回呼
        final TransferCallback workerCallback = getWrappedCallback();
        
        // 通知開始傳輸
        if (workerCallback != null) workerCallback.onStart(fileLength);
        
        // 建立區塊佇列
        final ConcurrentLinkedQueue<ChunkInfo> chunkQueue = new ConcurrentLinkedQueue<>();
        
        // 填充佇列
        populateChunkQueue(fileLength, chunkQueue);
        
        // 追蹤已建立的 SocketChannels，以便在結束時關閉
        final List<SocketChannel> channels = new ArrayList<>(threadCount);
        
        // 使用 Virtual Threads 執行緒池，為每個任務創建一個虛擬執行緒
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // 開啟檔案通道
            try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
                // 建立 workers
                for (int i = 0; i < threadCount; i++) {
                    SocketChannel socketChannel = null;
                    try {
                        // 建立連接
                        socketChannel = SocketChannel.open();
                        socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        socketChannel.configureBlocking(true);
                        socketChannel.connect(new InetSocketAddress(host, port));
                        channels.add(socketChannel);

                        // 提交任務到執行緒池
                        final SocketChannel finalSocketChannel = socketChannel;
                        pool.submit(() -> {
                            new SenderWorker(finalSocketChannel, fileChannel, chunkQueue, workerCallback).run();
                            return null;
                        });
                    } catch (IOException e) {
                        // 處理連接失敗的情況
                        if (socketChannel != null && socketChannel.isOpen()) {
                            try {
                                socketChannel.close();
                            } catch (IOException sce) {
                                LogPanel.log("關閉失敗的 socket channel 時出錯: " + sce.getMessage());
                            }
                        }
                        if (workerCallback != null) workerCallback.onError(new IOException("Worker " + i + " 連接失敗: " + e.getMessage(), e));
                    }
                }

                // 等待所有任務完成
                pool.shutdown();
                if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
                    pool.shutdownNow();
                    if (workerCallback != null && !errorReportedByWorker.get()) {
                        workerCallback.onError(new IOException("SendFile 任務超時"));
                    }
                } else if (!errorReportedByWorker.get()) {
                    // 所有工作都正常完成
                    if (originalCallback != null) originalCallback.onComplete();
                }
            } finally {
                // 確保所有 channel 都被關閉
                for (SocketChannel sc : channels) {
                    if (sc != null && sc.isOpen()) {
                        try {
                            sc.close();
                        } catch (IOException e) {
                            LogPanel.log("SendFile: 關閉 sender socket channel 時出錯: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            LogPanel.log("執行緒池處理時發生錯誤: " + e.getMessage());
            workerCallback.onError(e);
        } finally {
            // 關閉所有通道
            for (SocketChannel channel : channels) {
                try {
                    channel.close();
                } catch (IOException e) {
                    LogPanel.log("關閉通道時出錯: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 填充區塊佇列，將檔案分割成多個區塊。
     * @param fileLength 檔案的總長度。
     * @param chunkQueue 區塊佇列。
     */
    private void populateChunkQueue(long fileLength, ConcurrentLinkedQueue<ChunkInfo> chunkQueue) {
        if (fileLength <= 0) {
            return; // 檔案為空，不需處理
        }

        // 計算每個執行緒的基本區塊大小，至少 8KB
        long basicChunkSize = Math.max(8 * 1024, fileLength / (threadCount * 2));
        
        // 建立均勻分佈的區塊
        long remainingBytes = fileLength;
        long currentOffset = 0;

        while (remainingBytes > 0) {
            long chunkSize = Math.min(basicChunkSize, remainingBytes);
            chunkQueue.add(new ChunkInfo(currentOffset, chunkSize));
            currentOffset += chunkSize;
            remainingBytes -= chunkSize;
        }
        
        LogPanel.log("已將檔案分割成 " + chunkQueue.size() + " 個區塊，每個基本區塊大小約為 " + basicChunkSize + " 位元組");
    }

    /**
     * SenderWorker 類別負責在單獨的執行緒中傳輸檔案的區塊。
     */
    private static class SenderWorker implements Runnable {
        private final SocketChannel socketChannel;
        private final FileChannel fileChannel;
        private final ConcurrentLinkedQueue<ChunkInfo> chunkQueue;
        private final TransferCallback callback;

        public SenderWorker(SocketChannel socketChannel, FileChannel fileChannel, ConcurrentLinkedQueue<ChunkInfo> chunkQueue, TransferCallback callback) {
            this.socketChannel = socketChannel;
            this.fileChannel = fileChannel;
            this.chunkQueue = chunkQueue;
            this.callback = callback;
        }

        @Override
        public void run() {
            try {
                ChunkInfo chunk;
                ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024); // 使用 64KB 的直接緩衝區
                
                // 從佇列中取出區塊並處理，直到佇列為空
                while ((chunk = chunkQueue.poll()) != null) {
                    long bytesLeftInChunk = chunk.length;
                    long chunkOffset = chunk.offset;

                    while (bytesLeftInChunk > 0) {
                        buffer.clear();
                        
                        // 限制緩衝區大小為剩餘區塊大小或緩衝區容量中較小的一個
                        int bytesToRead = (int) Math.min(buffer.capacity(), bytesLeftInChunk);
                        buffer.limit(bytesToRead);

                        // 從檔案讀取資料到緩衝區
                        int bytesRead = fileChannel.read(buffer, chunkOffset);
                        if (bytesRead <= 0) {
                            break; // 已讀取到檔案末尾
                        }

                        // 準備寫入
                        buffer.flip();
                        
                        // 確保全部資料寫入 socket
                        int totalBytesWritten = 0;
                        while (buffer.hasRemaining()) {
                            int bytesWritten = socketChannel.write(buffer);
                            if (bytesWritten <= 0) {
                                throw new IOException("無法寫入 socket");
                            }
                            totalBytesWritten += bytesWritten;
                        }
                        
                        // 更新進度
                        bytesLeftInChunk -= totalBytesWritten;
                        chunkOffset += totalBytesWritten;
                        
                        // 通知回呼
                        if (callback != null) {
                            callback.onProgress(totalBytesWritten);
                        }
                    }
                }
                
                // 正常結束，關閉 socket channel
                socketChannel.close();
                
            } catch (Exception e) {
                LogPanel.log("SenderWorker 遇到錯誤: " + e.getMessage());
                try {
                    if (socketChannel.isOpen()) {
                        socketChannel.close();
                    }
                } catch (IOException closeEx) {
                    LogPanel.log("關閉 socket channel 時出錯: " + closeEx.getMessage());
                }
                
                if (callback != null) {
                    callback.onError(e);
                }
            }
        }
    }
}
