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
    
    // 區塊比對設定
    private static final int COMPARISON_BLOCK_SIZE = 64 * 1024; // 64KB 比對區塊大小
    private static final int FAST_COMPARE_SIZE = 8; // 使用 long (8 bytes) 進行快速比較

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
    }

    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf;
        private final TransferCallback callback;
        private final AtomicLong totalBytesReceived;
        private final AtomicLong totalBytesSkipped;
        @SuppressWarnings("unused")
        private final long expectedFileLength;
        private final FileChannel fileChannel;
        private final String outputFilePath;

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
            this.outputFilePath = outputFilePath;
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
        }

        /**
         * 智能區塊比對和寫入
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
            
            // 檢查檔案是否已存在相同位置的資料
            ComparisonResult result = new ComparisonResult();
            
            try {
                // 讀取現有檔案資料進行比對
                ByteBuffer existingDataBuffer = ByteBuffer.allocateDirect((int) chunkLength);
                
                if (fileChannel != null) {
                    fileChannel.position(chunkOffset);
                    
                    // 檢查檔案是否已有足夠長度
                    long fileSize = fileChannel.size();
                    if (chunkOffset + chunkLength <= fileSize) {
                        // 檔案已有足夠長度，讀取現有資料
                        while (existingDataBuffer.hasRemaining()) {
                            int bytesRead = fileChannel.read(existingDataBuffer);
                            if (bytesRead == -1) break;
                        }
                        existingDataBuffer.flip();
                        
                        // 進行快速區塊比對
                        result = compareAndWriteBlocks(newDataBuffer, existingDataBuffer, chunkOffset);
                    } else {
                        // 檔案長度不足，直接寫入所有新資料
                        result = writeAllNewData(newDataBuffer, chunkOffset);
                    }
                } else {
                    // 沒有 fileChannel，直接寫入
                    result = writeAllNewData(newDataBuffer, chunkOffset);
                }
                
            } catch (IOException e) {
                // 比對失敗，直接寫入新資料
                LogPanel.log("ReceiverOptimized: 比對失敗，直接寫入: " + e.getMessage());
                newDataBuffer.rewind();
                result = writeAllNewData(newDataBuffer, chunkOffset);
            }
            
            return result;
        }

        /**
         * 快速區塊比對並寫入不同的部分
         */
        private ComparisonResult compareAndWriteBlocks(ByteBuffer newData, ByteBuffer existingData, 
                                                     long chunkOffset) throws IOException {
            ComparisonResult result = new ComparisonResult();
            
            newData.rewind();
            existingData.rewind();
            
            int blockSize = COMPARISON_BLOCK_SIZE;
            long currentOffset = chunkOffset;
            
            while (newData.hasRemaining() && existingData.hasRemaining()) {
                int remainingBytes = Math.min(newData.remaining(), existingData.remaining());
                int currentBlockSize = Math.min(blockSize, remainingBytes);
                
                // 建立當前區塊的 buffer
                ByteBuffer newBlock = newData.slice();
                newBlock.limit(currentBlockSize);
                
                ByteBuffer existingBlock = existingData.slice();
                existingBlock.limit(currentBlockSize);
                
                // 快速比對區塊
                if (fastBlockCompare(newBlock, existingBlock)) {
                    // 區塊相同，跳過
                    result.bytesSkipped += currentBlockSize;
                } else {
                    // 區塊不同，寫入新資料
                    if (fileChannel != null) {
                        fileChannel.position(currentOffset);
                        newBlock.rewind();
                        while (newBlock.hasRemaining()) {
                            fileChannel.write(newBlock);
                        }
                        fileChannel.force(false); // 強制寫入
                    }
                    result.bytesWritten += currentBlockSize;
                }
                
                // 移動 buffer 位置
                newData.position(newData.position() + currentBlockSize);
                existingData.position(existingData.position() + currentBlockSize);
                currentOffset += currentBlockSize;
                result.totalProcessed += currentBlockSize;
            }
            
            // 處理剩餘的新資料（如果有的話）
            if (newData.hasRemaining()) {
                int remainingBytes = newData.remaining();
                if (fileChannel != null) {
                    fileChannel.position(currentOffset);
                    while (newData.hasRemaining()) {
                        fileChannel.write(newData);
                    }
                    fileChannel.force(false);
                }
                result.bytesWritten += remainingBytes;
                result.totalProcessed += remainingBytes;
            }
            
            return result;
        }

        /**
         * 寫入所有新資料（當無法比對時）
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

        /**
         * 使用 long (8 bytes) 進行快速區塊比對
         */
        private boolean fastBlockCompare(ByteBuffer buffer1, ByteBuffer buffer2) {
            if (buffer1.remaining() != buffer2.remaining()) {
                return false;
            }
            
            buffer1.rewind();
            buffer2.rewind();
            
            // 使用 long 進行快速比較
            while (buffer1.remaining() >= 8 && buffer2.remaining() >= 8) {
                long long1 = buffer1.getLong();
                long long2 = buffer2.getLong();
                if (long1 != long2) {
                    return false;
                }
            }
            
            // 比較剩餘的 bytes
            while (buffer1.hasRemaining() && buffer2.hasRemaining()) {
                if (buffer1.get() != buffer2.get()) {
                    return false;
                }
            }
            
            return !buffer1.hasRemaining() && !buffer2.hasRemaining();
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
