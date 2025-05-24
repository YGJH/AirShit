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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final AtomicInteger successfulConnections = new AtomicInteger(0); // 追蹤成功建立的連接數

    // 用於連接建立的重試參數
    private static final int CONNECTION_RETRY_ATTEMPTS = 3;
    private static final int CONNECTION_RETRY_DELAY_MS = 500;
    private static final int CONNECTION_SETUP_TIMEOUT_MS = 5000;

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
        this.threadCount = Math.max(1, threadCount); // 確保至少有一個執行緒
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
            public void onProgress(long bytesSent) {
                if (originalCallback != null) originalCallback.onProgress(bytesSent);
            }

            @Override
            public void onComplete() {
                if (originalCallback != null) originalCallback.onComplete();
            }

            @Override
            public void onError(Exception e) {
                errorReportedByWorker.set(true);
                if (originalCallback != null) originalCallback.onError(e);
            }
        };
    }

    /**
     * 開始檔案傳輸過程。
     * 此方法會設定執行緒池，準備檔案區塊，並啟動 SenderWorker 執行緒來傳送資料。
     * @throws IOException 如果發生 I/O 錯誤，例如無法開啟檔案或網路錯誤。
     * @throws InterruptedException 如果執行緒在等待時被中斷。
     */
    public void start() throws IOException, InterruptedException {
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            throw new IOException("檔案不存在，不是一個檔案，或無法讀取: " + file.getAbsolutePath());
        }

        // 檢查檔案大小
        long fileLength = file.length();
        if (fileLength <= 0) {
            throw new IOException("檔案大小為 0 或無法確定: " + file.getAbsolutePath());
        }

        // 使用者請求的執行緒數
        // 如果檔案太小，降低執行緒數
        int poolSize = Math.min(threadCount, (int)(fileLength / (64 * 1024)) + 1);
        poolSize = Math.max(1, poolSize); // 至少使用 1 個執行緒
        
        LogPanel.log("開始傳送檔案: " + file.getName() + ", 大小: " + fileLength + " 位元組, 使用 " + poolSize + " 個執行緒 (Virtual Threads)");

        // 準備傳送給 Worker 的回呼
        TransferCallback workerCallback = getWrappedCallback();

        // 創建區塊佇列並填充
        ConcurrentLinkedQueue<ChunkInfo> chunkQueue = new ConcurrentLinkedQueue<>();
        populateChunkQueue(fileLength, chunkQueue);

        // 追蹤所有 channels 以便在出錯時可以關閉它們
        List<SocketChannel> channels = new ArrayList<>(poolSize);

        // 使用 Virtual Threads 執行緒池
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

        // 發送準備就緒訊號並等待確認
        // 這是新增的：向接收方發送一個訊號，告知將要建立的連接數量，並等待確認
        try (SocketChannel setupChannel = SocketChannel.open()) {
            setupChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            setupChannel.configureBlocking(true);
            setupChannel.connect(new InetSocketAddress(host, port));
            
            // 傳送設定訊息：SETUP|<threadCount>|<fileId>
            String setupMessage = "SETUP|" + poolSize + "|" + file.getName().hashCode();
            ByteBuffer setupBuffer = ByteBuffer.wrap(setupMessage.getBytes());
            while (setupBuffer.hasRemaining()) {
                setupChannel.write(setupBuffer);
            }
            
            // 等待接收方確認
            ByteBuffer responseBuffer = ByteBuffer.allocate(64);
            setupChannel.socket().setSoTimeout(CONNECTION_SETUP_TIMEOUT_MS);
            int bytesRead = setupChannel.read(responseBuffer);
            if (bytesRead > 0) {
                responseBuffer.flip();
                byte[] responseBytes = new byte[responseBuffer.remaining()];
                responseBuffer.get(responseBytes);
                String response = new String(responseBytes);
                
                if (!"READY".equals(response.trim())) {
                    throw new IOException("接收方回應不是 READY: " + response);
                }
                LogPanel.log("接收方已準備好接收 " + poolSize + " 個連接");
            } else {
                throw new IOException("讀取接收方確認時發生錯誤");
            }
        } catch (IOException e) {
            LogPanel.log("設定連接時發生錯誤: " + e.getMessage());
            if (workerCallback != null) workerCallback.onError(new IOException("無法建立設定連接: " + e.getMessage(), e));
            return;
        }

        // 提供一個短暫的延遲，確保接收方已完全準備好
        Thread.sleep(200);
        
        try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            // 建立並啟動所有工作執行緒
            for (int i = 0; i < poolSize; i++) {
                final int workerIndex = i;
                SocketChannel socketChannel = null;
                
                // 嘗試建立連接，帶有重試機制
                for (int attempt = 0; attempt < CONNECTION_RETRY_ATTEMPTS; attempt++) {
                    try {
                        socketChannel = SocketChannel.open();
                        socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                        socketChannel.configureBlocking(true);
                        socketChannel.connect(new InetSocketAddress(host, port));
                        
                        // 傳送工作執行緒識別訊息：WORKER|<index>|<fileId>
                        String workerIdMessage = "WORKER|" + workerIndex + "|" + file.getName().hashCode();
                        ByteBuffer idBuffer = ByteBuffer.wrap(workerIdMessage.getBytes());
                        while (idBuffer.hasRemaining()) {
                            socketChannel.write(idBuffer);
                        }
                        
                        // 等待確認
                        ByteBuffer ackBuffer = ByteBuffer.allocate(16);
                        socketChannel.socket().setSoTimeout(2000);
                        int bytesRead = socketChannel.read(ackBuffer);
                        if (bytesRead > 0) {
                            ackBuffer.flip();
                            byte[] ackBytes = new byte[ackBuffer.remaining()];
                            ackBuffer.get(ackBytes);
                            String ack = new String(ackBytes);
                            
                            if ("ACK".equals(ack.trim())) {
                                channels.add(socketChannel);
                                successfulConnections.incrementAndGet();
                                break; // 成功建立連接，跳出重試迴圈
                            } else {
                                throw new IOException("接收方回應不是 ACK: " + ack);
                            }
                        } else {
                            throw new IOException("讀取接收方確認時發生錯誤");
                        }
                    } catch (IOException e) {
                        if (socketChannel != null && socketChannel.isOpen()) {
                            try {
                                socketChannel.close();
                            } catch (IOException sce) {
                                LogPanel.log("關閉失敗的 socket channel 時出錯: " + sce.getMessage());
                            }
                        }
                        
                        if (attempt == CONNECTION_RETRY_ATTEMPTS - 1) {
                            // 最後一次嘗試也失敗
                            LogPanel.log("Worker " + workerIndex + " 連接失敗 (嘗試 " + (attempt+1) + "/" + CONNECTION_RETRY_ATTEMPTS + "): " + e.getMessage());
                            if (workerCallback != null) workerCallback.onError(new IOException("Worker " + workerIndex + " 連接失敗: " + e.getMessage(), e));
                        } else {
                            // 還有重試機會
                            LogPanel.log("Worker " + workerIndex + " 連接嘗試 " + (attempt+1) + " 失敗，正在重試...");
                            Thread.sleep(CONNECTION_RETRY_DELAY_MS);
                        }
                    }
                }
                
                // 如果成功建立連接，提交工作任務
                if (socketChannel != null && socketChannel.isOpen()) {
                    // 提交 Virtual Thread 任務
                    pool.submit(new SenderWorker(socketChannel, fileChannel, chunkQueue, workerCallback, workerIndex));
                }
            }
            
            // 檢查連接建立情況
            if (successfulConnections.get() == 0) {
                throw new IOException("無法建立任何資料連接");
            } else if (successfulConnections.get() < poolSize) {
                LogPanel.log("警告：只建立了 " + successfulConnections.get() + "/" + poolSize + " 個連接");
            }

            // 等待所有任務完成
            pool.shutdown();
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
                pool.shutdownNow();
                if (workerCallback != null && !errorReportedByWorker.get()) {
                    workerCallback.onError(new IOException("SendFile 任務超時。"));
                }
            }
        } finally {
            // 清理資源
            for (SocketChannel sc : channels) {
                if (sc.isOpen()) {
                    try {
                        sc.close();
                    } catch (IOException e) {
                        LogPanel.log("SendFile: 關閉 sender socket channel 時出錯: " + e.getMessage());
                    }
                }
            }
            if (pool != null && !pool.isTerminated()) {
                pool.shutdownNow();
            }
        }
    }

    /**
     * 填充區塊佇列，將檔案分割成多個區塊。
     * @param fileLength 檔案的總長度。
     * @param chunkQueue 區塊佇列。
     */
    private void populateChunkQueue(long fileLength, ConcurrentLinkedQueue<ChunkInfo> chunkQueue) {
        // 基本區塊大小計算：
        // 假設檔案大小為 100MB，執行緒數為 4，則：
        // - 我們希望每個執行緒處理多個區塊以平衡負載
        // - 區塊不要太小（至少 1MB）以減少佇列操作開銷
        // - 區塊不要太大（不超過 64MB）以確保內存使用合理
        
        // 首先，計算每個執行緒大約需要處理的數據量
        long bytesPerThread = fileLength / threadCount;
        
        // 每個基本區塊的目標大小：每個執行緒處理 3-5 個區塊
        long targetChunkSize = bytesPerThread / 4;
        
        // 確保區塊大小在合理範圍內
        long baseChunkSize = Math.max(1024 * 1024, Math.min(targetChunkSize, 64 * 1024 * 1024));
        
        // 計算區塊數量
        int numChunks = (int) Math.ceil((double) fileLength / baseChunkSize);
        
        // 記錄日誌
        LogPanel.log("已將檔案分割成 " + numChunks + " 個區塊，每個基本區塊大小約為 " + baseChunkSize + " 位元組");
        
        // 將檔案分割成區塊並加入佇列
        long remainingBytes = fileLength;
        long currentOffset = 0;
        
        while (remainingBytes > 0) {
            // 最後一個區塊可能小於 baseChunkSize
            long currentChunkSize = Math.min(baseChunkSize, remainingBytes);
            chunkQueue.add(new ChunkInfo(currentOffset, currentChunkSize));
            
            currentOffset += currentChunkSize;
            remainingBytes -= currentChunkSize;
        }
    }

    /**
     * ChunkInfo 類別表示檔案中的一個區塊，包含其在檔案中的偏移和長度。
     */
    private static class ChunkInfo {
        public final long offset;
        public final long length;

        public ChunkInfo(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }
    }

    /**
     * SenderWorker 類別負責在單獨的執行緒中傳輸檔案的區塊。
     */
    private static class SenderWorker implements Runnable {
        private final SocketChannel socketChannel;
        private final FileChannel fileChannel;
        private final ConcurrentLinkedQueue<ChunkInfo> chunkQueue;
        private final TransferCallback callback;
        private final int workerIndex;
        private final AtomicBoolean hasReportedError = new AtomicBoolean(false);

        public SenderWorker(SocketChannel socketChannel, FileChannel fileChannel, 
                           ConcurrentLinkedQueue<ChunkInfo> chunkQueue, TransferCallback callback, int workerIndex) {
            this.socketChannel = socketChannel;
            this.fileChannel = fileChannel;
            this.chunkQueue = chunkQueue;
            this.callback = callback;
            this.workerIndex = workerIndex;
        }

        @Override
        public void run() {
            try {
                ChunkInfo chunk;
                ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024); // 使用 64KB 的直接緩衝區
                long totalBytesSent = 0;
                
                // 從佇列中取出區塊並處理，直到佇列為空
                while ((chunk = chunkQueue.poll()) != null) {
                    long bytesLeftInChunk = chunk.length;
                    long chunkOffset = chunk.offset;

                    while (bytesLeftInChunk > 0) {
                        buffer.clear();
                        
                        int bytesToRead = (int) Math.min(buffer.capacity(), bytesLeftInChunk);
                        buffer.limit(bytesToRead);

                        int bytesReadFromFile = fileChannel.read(buffer, chunkOffset);
                        if (bytesReadFromFile <= 0) {
                            LogPanel.log("SenderWorker " + workerIndex + ": fileChannel.read 返回 " + bytesReadFromFile + 
                                        " (讀取位置: " + chunkOffset + ")。終止此 worker。");
                            
                            if (callback != null && !hasReportedError.getAndSet(true)) {
                                callback.onError(new IOException("Worker " + workerIndex + " 無法從檔案讀取資料 (偏移: " + chunkOffset + ")"));
                            }
                            return;
                        }

                        buffer.flip();
                        
                        try {
                            int totalBytesWrittenThisPass = 0;
                            while (buffer.hasRemaining()) {
                                int bytesWrittenToSocket = socketChannel.write(buffer);
                                if (bytesWrittenToSocket <= 0) {
                                    throw new IOException("無法寫入 socket channel，write 返回 " + bytesWrittenToSocket);
                                }
                                totalBytesWrittenThisPass += bytesWrittenToSocket;
                            }
                            
                            bytesLeftInChunk -= totalBytesWrittenThisPass;
                            chunkOffset += totalBytesWrittenThisPass;
                            totalBytesSent += totalBytesWrittenThisPass;
                            
                            if (callback != null) {
                                callback.onProgress(totalBytesWrittenThisPass);
                            }
                        } catch (IOException e) {
                            // 檢查是否是 "Connection reset by peer" 錯誤
                            if (e.getMessage() != null && e.getMessage().contains("Connection reset by peer")) {
                                // 如果佇列為空且已經傳送了大部分資料，則可能是正常完成
                                if (chunkQueue.isEmpty() && totalBytesSent > 0) {
                                    LogPanel.log("SenderWorker " + workerIndex + ": 連接重置，但似乎在傳輸接近完成時發生，視為正常完成");
                                    return;
                                } else {
                                    LogPanel.log("SenderWorker " + workerIndex + ": 連接被對方重置，可能是接收方關閉了連接");
                                }
                            }
                            throw e; // 重新拋出，由外層處理
                        }
                    }
                }
                
                // 所有區塊處理完成，發送終止訊號
                try {
                    // 傳送 EOF 訊號
                    ByteBuffer eofBuffer = ByteBuffer.wrap("EOF".getBytes());
                    while (eofBuffer.hasRemaining()) {
                        socketChannel.write(eofBuffer);
                    }
                    
                    // 優雅地關閉輸出流
                    socketChannel.shutdownOutput();
                    LogPanel.log("SenderWorker " + workerIndex + ": 已成功傳送所有指派的區塊 (" + totalBytesSent + " 位元組) 並關閉輸出流");
                    
                    // 等待接收方確認
                    ByteBuffer closeAckBuffer = ByteBuffer.allocate(16);
                    socketChannel.socket().setSoTimeout(5000); // 5秒超時
                    int bytesRead = socketChannel.read(closeAckBuffer);
                    
                    if (bytesRead > 0) {
                        closeAckBuffer.flip();
                        byte[] ackBytes = new byte[closeAckBuffer.remaining()];
                        closeAckBuffer.get(ackBytes);
                        String ack = new String(ackBytes);
                        
                        if ("CLOSE_ACK".equals(ack.trim())) {
                            LogPanel.log("SenderWorker " + workerIndex + ": 接收到接收方的關閉確認");
                        } else {
                            LogPanel.log("SenderWorker " + workerIndex + ": 接收到未預期的關閉回應: " + ack);
                        }
                    }
                } catch (IOException closeEx) {
                    // 關閉時發生錯誤不視為傳輸失敗
                    LogPanel.log("SenderWorker " + workerIndex + ": 在發送終止訊號或關閉時發生非嚴重錯誤: " + closeEx.getMessage());
                } finally {
                    // 關閉 socket
                    if (socketChannel.isOpen()) {
                        try {
                            socketChannel.close();
                        } catch (IOException e) {
                            LogPanel.log("SenderWorker " + workerIndex + ": 關閉 socketChannel 時發生錯誤: " + e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                LogPanel.log("SenderWorker " + workerIndex + " 遇到 IOException: " + e.getMessage());
                
                // 報告錯誤（只報告一次）
                if (callback != null && !hasReportedError.getAndSet(true)) {
                    callback.onError(e);
                }
                
                // 確保 socket 被關閉
                if (socketChannel.isOpen()) {
                    try {
                        socketChannel.close();
                    } catch (IOException closeEx) {
                        LogPanel.log("SenderWorker " + workerIndex + ": 在異常後關閉 socketChannel 時發生錯誤: " + closeEx.getMessage());
                    }
                }
            }
        }
    }
}
