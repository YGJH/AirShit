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
 * 超高效能檔案接收器，使用 Zero Copy + Virtual Threads + 智能區塊比對
 */
public class ReceiverOptimized {
    private final ServerSocket serverSocket;
    
    // 智能比對設定 - 參考 Google CDC 理念優化
    private static final int BATCH_WRITE_THRESHOLD = 256 * 1024; // 256KB 批次寫入閾值
    
    // 智能模式切換
    public enum ComparisonMode {
        PERFORMANCE_FIRST,  // 優先性能，跳過比對
        SMART_COMPARISON,   // 智能比對，平衡性能與去重
        AGGRESSIVE_DEDUP    // 激進去重，犧牲性能換取最大去重效益
    }
    
    private static final ComparisonMode DEFAULT_MODE = ComparisonMode.PERFORMANCE_FIRST;
    
    // 可以在運行時切換的模式
    private static ComparisonMode currentMode = DEFAULT_MODE;
    
    /**
     * 設定接收器的比對模式
     * @param mode 比對模式
     */
    public static void setComparisonMode(ComparisonMode mode) {
        currentMode = mode;
        LogPanel.log("ReceiverOptimized: 切換到 " + getModeDescription(mode) + " 模式");
    }
    
    /**
     * 取得當前比對模式
     */
    public static ComparisonMode getCurrentMode() {
        return currentMode;
    }
    
    /**
     * 取得模式描述
     */
    private static String getModeDescription(ComparisonMode mode) {
        switch (mode) {
            case PERFORMANCE_FIRST: return "效能優先";
            case SMART_COMPARISON: return "智能比對";
            case AGGRESSIVE_DEDUP: return "激進去重";
            default: return "未知模式";
        }
    }

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
                    " (預期大小: " + fileLength + " bytes, 執行緒數: " + threadCount + 
                    ", 比對模式: " + getModeDescription(currentMode) + ")");

        if (fileLength == 0) {
            if (cb != null) {
                cb.onStart(0, outputFile);
                cb.onComplete();
            }
            return true;
        }

        AtomicLong totalBytesReceived = new AtomicLong(0);
        AtomicLong totalBytesSkipped = new AtomicLong(0);
        
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            // 預先分配檔案空間
            raf.setLength(fileLength);
            
            if (cb != null) {
                cb.onStart(fileLength, outputFile);
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
                            
                            ReceiverWorker worker = new ReceiverWorker(socket, raf, cb, 
                                totalBytesReceived, totalBytesSkipped, fileLength, outputFile);
                            worker.run();
                            
                        } catch (IOException e) {
                            LogPanel.log("ReceiverOptimized Worker: 連接錯誤: " + e.getMessage());
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
                long totalSkipped = totalBytesSkipped.get();
                long totalProcessed = totalReceived + totalSkipped;
                
                LogPanel.log("ReceiverOptimized: 總共處理 " + totalProcessed + " bytes，" +
                           "寫入 " + totalReceived + " bytes，" +
                           "跳過 " + totalSkipped + " bytes (相同區塊)，" +
                           "預期 " + fileLength + " bytes");
                
                if (totalProcessed == fileLength && allCompleted && cb != null) {
                    cb.onComplete();
                }
                
                return totalProcessed == fileLength && allCompleted;
            }
        } catch (IOException e) {
            LogPanel.log("ReceiverOptimized: 檔案操作錯誤: " + e.getMessage());
            if (cb != null) {
                cb.onError(e);
            }
            return false;
        }
    }    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf;
        private final TransferCallback callback;
        private final AtomicLong totalBytesReceived;
        private final AtomicLong totalBytesSkipped;
        @SuppressWarnings("unused")
        private final long expectedFileLength;
        private final FileChannel fileChannel;

        public ReceiverWorker(Socket dataSocket, RandomAccessFile raf, TransferCallback callback, 
                            AtomicLong totalReceived, AtomicLong totalSkipped, 
                            long expectedFileLength, String outputFilePath) {
            this.dataSocket = dataSocket;
            this.raf = raf;
            this.callback = callback;
            this.totalBytesReceived = totalReceived;
            this.totalBytesSkipped = totalSkipped;
            this.expectedFileLength = expectedFileLength;
            this.fileChannel = (this.raf != null) ? this.raf.getChannel() : null;
            // outputFilePath 移除，因為不再使用
        }

        @Override
        public void run() {
            long totalBytesRead = 0;
            long totalBytesSkippedLocal = 0;
            
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
                        
                        // 接收 chunk 資料並進行智能比對
                        ComparisonResult result = receiveAndCompareChunkData(socketChannel, chunkOffset, chunkLength);
                        
                        totalBytesRead += result.bytesWritten;
                        totalBytesSkippedLocal += result.bytesSkipped;
                        
                        // 更新總進度
                        totalBytesReceived.addAndGet(result.bytesWritten);
                        totalBytesSkipped.addAndGet(result.bytesSkipped);
                        
                        // 報告進度 (包含跳過的字節，因為它們也是"處理過的")
                        if (callback != null) {
                            callback.onProgress(result.bytesWritten + result.bytesSkipped);
                        }
                        
                        if (result.totalProcessed < chunkLength) {
                            LogPanel.log("ReceiverOptimized Worker: 接收不完整，預期 " + chunkLength + 
                                       "，實際處理 " + result.totalProcessed);
                            break;
                        }
                    }
                    
                } catch (IOException e) {
                    LogPanel.log("ReceiverOptimized Worker: 資料傳輸錯誤: " + e.getMessage());
                    if (callback != null) {
                        callback.onError(e);
                    }
                }
                
                LogPanel.log("ReceiverOptimized Worker 完成，寫入了 " + totalBytesRead + 
                           " bytes，跳過了 " + totalBytesSkippedLocal + " bytes");
                
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
        }        /**
         * 高效能智能區塊比對和寫入 - 基於模式切換的優化
         */
        private ComparisonResult receiveAndCompareChunkData(ReadableByteChannel socketChannel, 
                                                          long chunkOffset, long chunkLength) throws IOException {
            
            if (fileChannel != null) {
                fileChannel.position(chunkOffset);
            }
            
            // 接收新資料到臨時 buffer
            ByteBuffer newDataBuffer = ByteBuffer.allocateDirect((int) chunkLength);
            long remainingBytes = chunkLength;
            
            // 先接收所有新資料
            while (remainingBytes > 0 && newDataBuffer.hasRemaining()) {
                int bytesRead = socketChannel.read(newDataBuffer);
                if (bytesRead == -1) {
                    break; // 連接關閉
                }
                if (bytesRead > 0) {
                    remainingBytes -= bytesRead;
                } else {
                    Thread.yield();
                }
            }
            
            newDataBuffer.flip(); // 準備讀取
              // 根據當前動態模式決定處理策略
            ComparisonMode mode = currentMode;
            
            switch (mode) {
                case PERFORMANCE_FIRST:
                    // 優先性能：直接寫入，不進行比對
                    return writeAllNewData(newDataBuffer, chunkOffset);
                    
                case SMART_COMPARISON:
                    // 智能比對：僅對大 chunk 進行比對
                    if (chunkLength >= BATCH_WRITE_THRESHOLD && fileChannel != null) {
                        try {
                            long fileSize = fileChannel.size();
                            if (chunkOffset + chunkLength <= fileSize) {
                                return optimizedBatchCompareAndWrite(newDataBuffer, chunkOffset, chunkLength);
                            }
                        } catch (IOException e) {
                            LogPanel.log("ReceiverOptimized: 智能比對失敗，回退到直接寫入: " + e.getMessage());
                        }
                    }
                    return writeAllNewData(newDataBuffer, chunkOffset);
                    
                case AGGRESSIVE_DEDUP:
                    // 激進去重：總是嘗試比對（原始行為）
                    if (fileChannel != null) {
                        try {
                            long fileSize = fileChannel.size();
                            if (chunkOffset + chunkLength <= fileSize) {
                                return optimizedBatchCompareAndWrite(newDataBuffer, chunkOffset, chunkLength);
                            }
                        } catch (IOException e) {
                            LogPanel.log("ReceiverOptimized: 激進比對失敗，回退到直接寫入: " + e.getMessage());
                        }
                    }
                    return writeAllNewData(newDataBuffer, chunkOffset);
                    
                default:
                    return writeAllNewData(newDataBuffer, chunkOffset);
            }
        }/**
         * 優化的批次比對和寫入 - 減少 I/O 操作次數
         */
        private ComparisonResult optimizedBatchCompareAndWrite(ByteBuffer newData, 
                                                            long chunkOffset, long chunkLength) throws IOException {
            ComparisonResult result = new ComparisonResult();
            
            // 使用較大的比對區塊 (512KB) 來減少 I/O 次數
            int batchSize = Math.min((int) chunkLength, 512 * 1024);
            ByteBuffer existingBatch = ByteBuffer.allocateDirect(batchSize);
            
            newData.rewind();
            long currentOffset = chunkOffset;
            
            while (newData.hasRemaining()) {
                int currentBatchSize = Math.min(batchSize, newData.remaining());
                
                // 讀取一批現有資料
                existingBatch.clear();
                existingBatch.limit(currentBatchSize);
                
                fileChannel.position(currentOffset);
                while (existingBatch.hasRemaining()) {
                    int bytesRead = fileChannel.read(existingBatch);
                    if (bytesRead == -1) break;
                }
                existingBatch.flip();
                
                // 準備新資料批次
                ByteBuffer newBatch = newData.slice();
                newBatch.limit(currentBatchSize);
                
                // 快速比對整個批次
                boolean batchesIdentical = fastBatchCompare(newBatch, existingBatch);
                
                if (batchesIdentical) {
                    // 整個批次相同，跳過
                    result.bytesSkipped += currentBatchSize;
                } else {
                    // 批次不同，寫入新資料
                    fileChannel.position(currentOffset);
                    newBatch.rewind();
                    while (newBatch.hasRemaining()) {
                        fileChannel.write(newBatch);
                    }
                    result.bytesWritten += currentBatchSize;
                }
                
                // 移動到下個批次
                newData.position(newData.position() + currentBatchSize);
                currentOffset += currentBatchSize;
                result.totalProcessed += currentBatchSize;
            }
            
            // 最後才強制刷新，減少磁碟操作
            if (result.bytesWritten > 0 && fileChannel != null) {
                fileChannel.force(false);
            }
            
            return result;
        }

        /**
         * 快速批次比對 - 使用 long 進行 8-byte 比對
         */
        private boolean fastBatchCompare(ByteBuffer buffer1, ByteBuffer buffer2) {
            if (buffer1.remaining() != buffer2.remaining()) {
                return false;
            }
            
            buffer1.rewind();
            buffer2.rewind();
            
            // 先用 long 進行快速比較
            while (buffer1.remaining() >= 8 && buffer2.remaining() >= 8) {
                long long1 = buffer1.getLong();
                long long2 = buffer2.getLong();
                if (long1 != long2) {
                    return false;
                }
            }
            
            // 比較剩餘 bytes
            while (buffer1.hasRemaining() && buffer2.hasRemaining()) {
                if (buffer1.get() != buffer2.get()) {
                    return false;
                }
            }
            
            return !buffer1.hasRemaining() && !buffer2.hasRemaining();
        }        /**
         * 寫入所有新資料（當無法比對或不需要比對時）
         */
        private ComparisonResult writeAllNewData(ByteBuffer newData, long chunkOffset) throws IOException {
            ComparisonResult result = new ComparisonResult();
            
            if (fileChannel != null) {
                fileChannel.position(chunkOffset);
                newData.rewind();
                while (newData.hasRemaining()) {
                    fileChannel.write(newData);
                }
                fileChannel.force(false);
            }
            
            result.bytesWritten = newData.capacity();
            result.totalProcessed = newData.capacity();
            
            return result;
        }
    }

    /**
     * 比對結果類別
     */
    private static class ComparisonResult {
        long bytesWritten = 0;    // 實際寫入的字節數
        long bytesSkipped = 0;    // 跳過的字節數（相同區塊）
        long totalProcessed = 0;  // 總共處理的字節數
    }
}
