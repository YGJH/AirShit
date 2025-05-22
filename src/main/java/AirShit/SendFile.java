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
        LogPanel.log("SendFile 建構函式: 收到 threadCount=" + threadCount);
        this.threadCount = Math.max(1, threadCount); // 確保至少有一個執行緒
        LogPanel.log("SendFile 建構函式: 設定 SendFile.this.threadCount=" + this.threadCount);
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
    }

    /**
     * 開始檔案傳輸過程。
     * 此方法會設定執行緒池，準備檔案區塊，並啟動 SenderWorker 執行緒來傳送資料。
     * @throws IOException 如果發生 I/O 錯誤，例如無法開啟檔案或網路錯誤。
     * @throws InterruptedException 如果執行緒在等待時被中斷。
     */
    public void start() throws IOException, InterruptedException {
        long fileLength = file.length(); // 獲取檔案長度
        TransferCallback workerCallback = getWrappedCallback(); // 使用包裝後的回呼給 worker

        LogPanel.log("SendFile 開始。檔案: " + file.getName() + ", 大小: " + fileLength + ", 執行緒數: " + threadCount);

        ConcurrentLinkedQueue<ChunkInfo> chunkQueue = new ConcurrentLinkedQueue<>(); // 儲存檔案區塊的佇列
        populateChunkQueue(fileLength, chunkQueue); // 填充區塊佇列

        // 檢查檔案長度與區塊生成情況
        if (fileLength > 0 && chunkQueue.isEmpty()) {
            LogPanel.log("錯誤: 檔案長度為 " + fileLength + " 但沒有生成任何區塊。");
            if (workerCallback != null) workerCallback.onError(new IOException("對於非空檔案，沒有生成區塊。"));
            return;
        }
        if (fileLength == 0 && chunkQueue.isEmpty()) {
            // 確保零位元組檔案有一個 (0,0) 的區塊，如果 populateChunkQueue 沒有添加它
            chunkQueue.offer(new ChunkInfo(0, 0));
            // LogPanel.log("SendFile: 為空檔案添加了一個零長度區塊。");
        }
        if (chunkQueue.isEmpty() && fileLength > 0){ // 理論上如果上述邏輯正確，不應發生
             LogPanel.log("錯誤: 對於非空檔案，在啟動 worker 前區塊佇列為空。");
             if (workerCallback != null) workerCallback.onError(new IOException("對於非空檔案，區塊佇列意外為空。"));
             return;
        }


        List<SocketChannel> channels = new ArrayList<>(); // 儲存已建立的 SocketChannel
        // 決定執行緒池大小：取設定的執行緒數和可用區塊數的最小值，但至少為 1。
        // chunkQueue will have at least one chunk (0,0 for empty file, or actual chunks)
        int poolSize = Math.min(this.threadCount, chunkQueue.size());
        poolSize = Math.max(1, poolSize); // 確保執行緒池中至少有一個執行緒。

        LogPanel.log("SendFile: Sender workers 的有效執行緒池大小: " + poolSize);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize); // 創建固定大小的執行緒池
        // AtomicInteger workersToStart = new AtomicInteger(poolSize); // 這個變數似乎未使用

        try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) { // 開啟檔案通道以讀取檔案內容
            for (int i = 0; i < poolSize; i++) { // 根據執行緒池大小，嘗試建立連接並提交 SenderWorker
                SocketChannel socketChannel = null;
                try {
                    socketChannel = SocketChannel.open(); // 開啟 SocketChannel
                    socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    socketChannel.configureBlocking(true); // 這裡為簡化使用阻塞模式
                    // LogPanel.log("SendFile: Worker " + i + " 嘗試連接到 " + host + ":" + port);
                    socketChannel.connect(new InetSocketAddress(host, port)); // 連接到接收端
                    channels.add(socketChannel); // 將成功的 channel 加入列表
                    // LogPanel.log("SendFile: Worker " + i + " 已連接到 " + host + ":" + port + " (本地: " + socketChannel.getLocalAddress() + ")");
                    pool.submit(new SenderWorker(socketChannel, fileChannel, chunkQueue, workerCallback /*, workersToStart*/)); // 提交 SenderWorker 任務
                } catch (IOException e) {
                    LogPanel.log("SendFile: Worker " + i + " 連接或啟動失敗: " + e.getMessage());
                    if (socketChannel != null && socketChannel.isOpen()) {
                        try { socketChannel.close(); } catch (IOException sce) { LogPanel.log("關閉失敗的 socket channel 時出錯: " + sce.getMessage());}
                    }
                    // workersToStart.decrementAndGet();
                    if (workerCallback != null) workerCallback.onError(new IOException("Worker " + i + " 連接失敗: " + e.getMessage(), e));
                    // 如果 worker 連接失敗，errorReportedByWorker 會被設定。
                }
            }
            // LogPanel.log("SendFile: 成功初始化的 sender workers 數量: " + channels.size() + " (預期啟動: " + poolSize + ")");

            if (channels.isEmpty() && (fileLength > 0 || poolSize > 0)) { // 如果沒有任何 worker 成功連接
                LogPanel.log("SendFile: 沒有 sender workers 可以連接。中止操作。");
                // workerCallback.onError 會為每個失敗的連接呼叫
                pool.shutdownNow(); // 確保執行緒池被關閉
                // errorReportedByWorker 應該為 true，所以下面的邏輯不會呼叫 onComplete。
                return;
            }

            pool.shutdown(); // 關閉執行緒池，不再接受新任務，但會完成已提交的任務
            // LogPanel.log("SendFile: 執行緒池關閉已啟動。等待終止...");
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) { // 等待所有任務完成，設定了超長超時時間
                LogPanel.log("SendFile: 執行緒池終止超時。強制關閉。");
                pool.shutdownNow(); // 強制關閉
                // 如果超時，並且之前沒有 worker 報告錯誤，則將此超時報告為錯誤。
                if (workerCallback != null && !errorReportedByWorker.get()) {
                    workerCallback.onError(new IOException("SendFile 任務超時。"));
                }
            } else {
                // LogPanel.log("SendFile: 所有 sender worker 任務已完成執行 (執行緒池已終止)。");
                // 檢查是否所有區塊都已處理且沒有 worker 報告錯誤
                if (!chunkQueue.isEmpty() && !errorReportedByWorker.get()) {
                    LogPanel.log("警告: SendFile workers 完成，但區塊佇列不為空。大小: " + chunkQueue.size());
                    if (workerCallback != null) {
                         workerCallback.onError(new IOException("傳輸未完成：workers 完成後仍有剩餘區塊。"));
                    }
                } else if (!errorReportedByWorker.get()) { // 如果佇列為空且沒有 worker 報告錯誤
                    LogPanel.log("SendFile: 所有資料已發送，無 worker 報告錯誤且佇列為空。呼叫 onComplete。");
                    // if (originalCallback != null) originalCallback.onComplete(); // 呼叫原始的 onComplete
                } else { // 如果有錯誤或佇列不為空
                    LogPanel.log("SendFile: 執行緒池已終止，但一個或多個 workers 報告錯誤或仍有剩餘區塊。SendFile 不會呼叫 onComplete。");
                }
            }

        } finally {
            // 清理資源：關閉所有 SocketChannel
            for (SocketChannel sc : channels) {
                if (sc.isOpen()) {
                    try {
                        sc.close();
                    } catch (IOException e) {
                        LogPanel.log("SendFile: 關閉 sender socket channel 時出錯: " + e.getMessage());
                    }
                }
            }
            // LogPanel.log("SendFile: 所有 sender socket channels 已嘗試關閉。");
            // 確保執行緒池最終被關閉
            if (!pool.isTerminated()) {
                LogPanel.log("SendFile: 在 final finally 區塊中強制關閉執行緒池。");
                pool.shutdownNow();
            }
        }
    }

    /**
     * 根據檔案長度和執行緒數量，將檔案分割成適當的區塊並填充到佇列中。
     * @param fileLength 檔案的總長度。
     * @param chunkQueue 用於儲存 ChunkInfo 物件的並行佇列。
     */
    private void populateChunkQueue(long fileLength, ConcurrentLinkedQueue<ChunkInfo> chunkQueue) {
        if (fileLength == 0) {
            // 對於零位元組檔案，SendFile.start() 會添加一個 (0,0) 的區塊。
            // 因此這裡可以直接返回，避免添加重複的 (0,0) 區塊或不必要的日誌。
            LogPanel.log("SendFile.populateChunkQueue: fileLength 為 0。單一 (0,0) 區塊將由 SendFile.start() 處理。");
            return;
        }
        LogPanel.log("SendFile.populateChunkQueue: 計算檔案長度為 " + fileLength + " 的區塊, SendFile.this.threadCount=" + this.threadCount);

        if (this.threadCount <= 0) { // 理論上建構函式已修正，但做防禦性檢查。
            LogPanel.log("SendFile.populateChunkQueue: 錯誤 - threadCount 為 " + this.threadCount + "。預設為 1 個區塊。");
            chunkQueue.offer(new ChunkInfo(0, fileLength));
            LogPanel.log("SendFile.populateChunkQueue: 完成。最終區塊佇列大小: " + chunkQueue.size());
            return;
        }
        
        final long SUPER_CHUNK_SIZE = 5L * 1024 * 1024 * 1024; // 5GB 作為超級區塊的大小
        final long MIN_ABSOLUTE_SUB_CHUNK_SIZE = 64 * 1024L;   // 子區塊的絕對最小大小 (例如 64KB)

        long currentOverallOffset = 0; // 追蹤在整個檔案中的當前偏移量
        int totalChunksGenerated = 0;

        while (currentOverallOffset < fileLength) {
            long remainingFileLengthForSuperChunk = fileLength - currentOverallOffset;
            long currentSuperChunkLength = Math.min(SUPER_CHUNK_SIZE, remainingFileLengthForSuperChunk); // 當前超級區塊的實際長度

            // LogPanel.log("SendFile.populateChunkQueue: 處理超級區塊，起始偏移: " + currentOverallOffset + ", 長度: " + currentSuperChunkLength);

            // 在當前超級區塊內，根據 threadCount 劃分子區塊
            if (this.threadCount == 1 || currentSuperChunkLength <= MIN_ABSOLUTE_SUB_CHUNK_SIZE) {
                // 如果只有一個執行緒，或者當前超級區塊本身小於或等於最小子區塊大小，
                // 則將整個超級區塊作為一個子區塊。
                chunkQueue.offer(new ChunkInfo(currentOverallOffset, currentSuperChunkLength));
                totalChunksGenerated++;
                // LogPanel.log("SendFile.populateChunkQueue: 超級區塊作為單一子區塊加入: offset=" + currentOverallOffset + ", length=" + currentSuperChunkLength);
            } else {
                // 多執行緒劃分當前超級區塊
                long idealSubChunkSize = (currentSuperChunkLength + this.threadCount - 1) / this.threadCount; // 理想子區塊大小 (向上取整)
                // 確保子區塊不小於最小絕對大小，但優先使用 idealSubChunkSize 以便利用多執行緒
                long actualSubChunkSize = Math.max(idealSubChunkSize, MIN_ABSOLUTE_SUB_CHUNK_SIZE); 

                // LogPanel.log("SendFile.populateChunkQueue: 超級區塊 (長度 " + currentSuperChunkLength + ") 細分: idealSubChunk=" + idealSubChunkSize + ", actualSubChunk=" + actualSubChunkSize);

                // 如果 super chunk 太小，即使 threadCount > 1, idealSubChunkSize 可能會小於 MIN_ABSOLUTE_SUB_CHUNK_SIZE
                // 導致 actualSubChunkSize 變大。如果 actualSubChunkSize 最終大於等於 super chunk 本身，則 super chunk 作為一個整體。
                if (actualSubChunkSize >= currentSuperChunkLength) {
                    chunkQueue.offer(new ChunkInfo(currentOverallOffset, currentSuperChunkLength));
                    totalChunksGenerated++;
                    // LogPanel.log("SendFile.populateChunkQueue: 計算後的 actualSubChunkSize >= 超級區塊長度。超級區塊作為單一子區塊加入: offset=" + currentOverallOffset + ", length=" + currentSuperChunkLength);
                } else {
                    long offsetWithinSuperChunk = 0;
                    while (offsetWithinSuperChunk < currentSuperChunkLength) {
                        long lengthForThisSubChunk = Math.min(actualSubChunkSize, currentSuperChunkLength - offsetWithinSuperChunk);
                        if (lengthForThisSubChunk > 0) {
                            chunkQueue.offer(new ChunkInfo(currentOverallOffset + offsetWithinSuperChunk, lengthForThisSubChunk));
                            totalChunksGenerated++;
                            // LogPanel.log("SendFile.populateChunkQueue: 加入子區塊: offset=" + (currentOverallOffset + offsetWithinSuperChunk) + ", length=" + lengthForThisSubChunk);
                        } else if (offsetWithinSuperChunk < currentSuperChunkLength) {
                            LogPanel.log("SendFile.populateChunkQueue: 警告 - 在超級區塊內偏移量 " + offsetWithinSuperChunk + " 處計算出零長度子區塊。");
                            break; 
                        }
                        offsetWithinSuperChunk += lengthForThisSubChunk;
                        if (lengthForThisSubChunk == 0 && offsetWithinSuperChunk < currentSuperChunkLength) { 
                             LogPanel.log("SendFile.populateChunkQueue: 錯誤 - 子區塊長度為0但未完成超級區塊的分割。");
                             break;
                        }
                    }
                }
            }
            currentOverallOffset += currentSuperChunkLength; 
            if (currentSuperChunkLength == 0 && currentOverallOffset < fileLength) { 
                LogPanel.log("SendFile.populateChunkQueue: 錯誤 - 超級區塊長度為0但未完成檔案的分割。");
                break;
            }
        }
        LogPanel.log("SendFile.populateChunkQueue: 完成。總共生成了 " + totalChunksGenerated + " 個區塊。最終區塊佇列大小: " + chunkQueue.size());
    }

    /**
     * SenderWorker 是一個 Runnable，負責從佇列中獲取檔案區塊 (ChunkInfo) 並透過 SocketChannel 將其傳送出去。
     * 每個 SenderWorker 處理一個或多個區塊。
     */
    private static class SenderWorker implements Runnable {
        private final SocketChannel socketChannel; // 此 worker 使用的 SocketChannel
        private final FileChannel fileChannel;     // 從中讀取檔案資料的 FileChannel
        private final ConcurrentLinkedQueue<ChunkInfo> chunkQueue; // 共享的區塊佇列
        private final TransferCallback callback; // 用於報告進度和錯誤的回呼 (已包裝)

        /**
         * SenderWorker 的建構函式。
         * @param socketChannel 用於傳輸資料的 SocketChannel。
         * @param fileChannel 從中讀取檔案資料的 FileChannel。
         * @param chunkQueue 共享的檔案區塊佇列。
         * @param callback 回呼介面。
         */
        public SenderWorker(SocketChannel socketChannel, FileChannel fileChannel,
                              ConcurrentLinkedQueue<ChunkInfo> chunkQueue, TransferCallback callback) {
            this.socketChannel = socketChannel;
            this.fileChannel = fileChannel;
            this.chunkQueue = chunkQueue;
            this.callback = callback;
        }

        @Override
        public void run() {
            String workerName = Thread.currentThread().getName(); // 獲取當前執行緒名稱，用於日誌
            // LogPanel.log("SenderWorker (" + workerName + ") 已啟動，socket: " +
            //              (socketChannel.isOpen() ? socketChannel.socket().getLocalPort() + " -> " + socketChannel.socket().getRemoteSocketAddress() : "已關閉"));
            
            // 檢查 SocketChannel 狀態
            if (!socketChannel.isOpen() || !socketChannel.isConnected()) {
                LogPanel.log("錯誤在 SenderWorker (" + workerName + "): SocketChannel 在啟動時未開啟或未連接。");
                if (callback != null) {
                    callback.onError(new IOException("SocketChannel 在 worker " + workerName + " 啟動時未開啟/連接"));
                }
                return;
            }

            try {
                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES); // 用於儲存區塊的偏移量和長度 (8+8=16 位元組)
                ChunkInfo chunk; // 當前處理的區塊
                boolean processedAtLeastOneChunk = false; // 標記此 worker 是否至少處理了一個區塊

                // 從佇列中不斷取出區塊進行處理，直到佇列為空
                while ((chunk = chunkQueue.poll()) != null) {
                    if (!socketChannel.isOpen()) { // 再次檢查 socket 狀態
                        throw new IOException("SocketChannel 在處理區塊 " + chunk + " 前已關閉 (worker " + workerName + ")");
                    }
                    processedAtLeastOneChunk = true;
                    LogPanel.log("SenderWorker (" + workerName + "): 取出區塊 " + chunk + ". 佇列近似大小: " + chunkQueue.size());
                    if (chunk.length < 0) { // 防禦性檢查，理論上不應有負長度區塊
                        LogPanel.log("SenderWorker (" + workerName + "): 跳過無效的負長度區塊: " + chunk);
                        continue;
                    }

                    // 準備並傳送區塊頭部資訊 (偏移量和長度)
                    headerBuffer.clear(); // 清空緩衝區以備寫入
                    headerBuffer.putLong(chunk.offset); // 寫入偏移量
                    headerBuffer.putLong(chunk.length); // 寫入長度
                    headerBuffer.flip(); // 切換到讀取模式，準備從緩衝區讀取資料進行傳送
                    LogPanel.log("SenderWorker (" + workerName + "): 正在為 " + chunk + " 傳送頭部資訊");
                    while (headerBuffer.hasRemaining()) { // 確保頭部資訊完全寫出
                        if (!socketChannel.isOpen()) throw new IOException("SocketChannel 在為區塊 " + chunk + " 傳送頭部資訊時關閉");
                        socketChannel.write(headerBuffer);
                    }

                    // 如果區塊長度為0 (例如，零位元組檔案的唯一區塊)，則只傳送頭部，不傳送資料主體
                    if (chunk.length == 0) {
                        LogPanel.log("SenderWorker (" + workerName + "): 已為 " + chunk + " 傳送零長度區塊頭部。無需傳送資料主體。");
                        continue; // 繼續處理下一個區塊
                    }

                    // 傳送區塊的實際資料
                    // LogPanel.log("SenderWorker (" + workerName + "): 正在為 " + chunk + " 傳送資料");
                    long bytesTransferredForThisChunk = 0; // 此區塊已傳送的位元組數
                    long loopStartTime = System.currentTimeMillis(); // 記錄開始傳送此區塊資料的時間，用於超時判斷

                    while (bytesTransferredForThisChunk < chunk.length) { // 循環直到此區塊的資料全部傳送完畢
                        if (!socketChannel.isOpen()) { // 檢查 socket 狀態
                            throw new IOException("SocketChannel 在為區塊 " + chunk + " 傳送資料時關閉 (已傳送 " + bytesTransferredForThisChunk + "/" + chunk.length + ") (worker " + workerName + ")");
                        }
                        // 檢查此區塊資料傳送是否超時 (例如，30分鐘)
                        if (System.currentTimeMillis() - loopStartTime > 30 * 60 * 1000) { 
                            throw new IOException("為區塊 " + chunk + " 傳送資料超時 (worker " + workerName + ")。已傳送 " + bytesTransferredForThisChunk + "/" + chunk.length);
                        }

                        // 使用 FileChannel.transferTo() 方法高效傳輸檔案資料到 SocketChannel
                        long transferredThisCall = fileChannel.transferTo(
                                chunk.offset + bytesTransferredForThisChunk, // 檔案中的起始偏移量
                                chunk.length - bytesTransferredForThisChunk, // 要傳輸的剩餘位元組數
                                socketChannel //目標 SocketChannel
                        );

                        if (transferredThisCall == 0 && (chunk.length - bytesTransferredForThisChunk > 0)) {
                             // 如果 transferTo 返回 0，表示 Socket 的傳送緩衝區可能已滿。
                             // 短暫休眠可以避免忙等待循環。
                             // 如果連接已斷開，後續的嘗試或超時會拋出 IOException。
                             LogPanel.log("SenderWorker (" + workerName + "): transferTo 為區塊 " + chunk + " 返回 0，剩餘 " + (chunk.length - bytesTransferredForThisChunk) + " 位元組。Socket 緩衝區可能已滿。短暫休眠。");
                             Thread.sleep(20); // 減少休眠時間，更頻繁地檢查。
                        } else if (transferredThisCall < 0) {
                            // transferTo 返回負值通常不正常，表示源通道 (檔案) 出錯或 EOF (對檔案不應發生)。
                            throw new IOException("fileChannel.transferTo 為區塊 " + chunk + " 返回 " + transferredThisCall + " (worker " + workerName + ")");
                        }
                        
                        bytesTransferredForThisChunk += transferredThisCall; // 更新此區塊已傳送的位元組數
                        if (callback != null && transferredThisCall > 0) {
                            callback.onProgress(transferredThisCall); // 報告進度
                        }
                    }
                    LogPanel.log("SenderWorker (" + workerName + "): 完成為區塊 " + chunk + " 傳送資料。此區塊總共傳送: " + bytesTransferredForThisChunk);
                }

                // 迴圈結束後，檢查此 worker 是否處理過任何區塊
                if (!processedAtLeastOneChunk) {
                    LogPanel.log("SenderWorker (" + workerName + "): 未處理任何區塊。佇列為空或 worker 啟動較晚。如果其他 workers 已處理所有區塊，則此情況正常。");
                }
                LogPanel.log("SenderWorker (" + workerName + "): 此 worker 的佇列中沒有更多區塊，或 worker 未處理任何區塊。Worker 即將結束。");

            } catch (IOException e) { // 捕獲 I/O 異常
                // 檢查異常是否由於通道關閉導致，這在接收端中止時很常見。
                String socketState = "未知";
                if (socketChannel != null) {
                    socketState = "isOpen=" + socketChannel.isOpen() + ", isConnected=" + socketChannel.isConnected() + 
                                  (socketChannel.socket() != null ? ", remoteAddr=" + socketChannel.socket().getRemoteSocketAddress() : "");
                }
                LogPanel.log("錯誤在 SenderWorker (" + workerName + "): " + e.getClass().getSimpleName() + " - " + e.getMessage() + ". Socket 狀態: " + socketState);
                // e.printStackTrace(); // 如果需要更詳細的堆疊追蹤
                if (callback != null) {
                    callback.onError(e); // 報告錯誤
                }
            } catch (InterruptedException e) { // 捕獲中斷異常
                LogPanel.log("SenderWorker (" + workerName + ") 被中斷: " + e.getMessage());
                Thread.currentThread().interrupt(); // 重設中斷狀態
                if (callback != null) {
                    callback.onError(e); // 報告錯誤
                }
            } finally {
                // SenderWorker 的 run 方法結束，無論成功或失敗。
                // SocketChannel 的關閉由 SendFile 主類的 finally 區塊統一處理。
                LogPanel.log("SenderWorker (" + workerName + ") run 方法完成。");
            }
        }
    }

    /**
     * ChunkInfo 類別用於儲存檔案區塊的偏移量和長度資訊。
     */
    private static class ChunkInfo {
        final long offset; // 區塊在原檔案中的起始偏移量
        final long length; // 區塊的長度

        /**
         * ChunkInfo 的建構函式。
         * @param offset 偏移量。
         * @param length 長度。
         */
        public ChunkInfo(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }

        @Override
        public String toString() {
            return "ChunkInfo{offset=" + offset + ", length=" + length + '}';
        }
    }
}