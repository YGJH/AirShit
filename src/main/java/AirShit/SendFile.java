package AirShit;

import java.io.*;
import java.net.Socket; // Keep for reference or if other parts use it, though ChunkSender won't
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SendFile: 將檔案分割成多段，並以多執行緒同時傳送給 Receiver。
 * 已改用 NIO FileChannel.transferTo()。
 */
public class SendFile {
    private final String host;
    private final int port;
    private final File file;
    // private final AtomicLong totalSent = new AtomicLong(0); // Progress is handled by callback
    private final TransferCallback callback;
    private int threadCount;

    public SendFile(String host, int port, File file, int threadCount, TransferCallback callback) {
        this.callback = callback;
        this.host = host;
        this.port = port;
        this.file = file;
        this.threadCount = threadCount;
    }

    public void start() throws IOException, InterruptedException {
        long fileLength = file.length();
        System.out.println("file size: " + fileLength);
        threadCount = Math.max(threadCount, 1);
        // 每個 "baseChunk" 最大約 5GB (long)，但實際傳輸時會再切分給 threadCount 個執行緒
        long baseChunkSize = Math.min(fileLength, 5L * 1024 * 1024 * 1024); // 5GB limit for a conceptual processing block
        long workerCount = (long) Math.ceil((double) fileLength / (double) baseChunkSize); // Number of base chunks
        
        // chunkSize 是每個執行緒在一個 baseChunk 內理想情況下處理的大小
        long chunkSize = (baseChunkSize + threadCount - 1) / threadCount;

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        long alreadySubmittedBytes = 0;

        for (int i = 0; i < workerCount; i++) {
            // processing 是當前這個 baseChunk 的實際大小
            long processing = Math.min(baseChunkSize, fileLength - alreadySubmittedBytes);
            
            for (int j = 0; j < threadCount; j++) {
                // offset 是這個執行緒負責的區塊在整個檔案中的起始位置
                long offset = (j * chunkSize) + alreadySubmittedBytes;
                
                // 如果計算出的 offset 已經超出了這個 baseChunk 的範圍，則無需再為此 baseChunk 分配執行緒
                if (offset >= alreadySubmittedBytes + processing) {
                    break; 
                }

                // tempChunkSize 是這個執行緒實際要傳輸的大小
                long tempChunkSize;
                if (j == threadCount - 1) {
                    // 最後一個執行緒處理這個 baseChunk 的剩餘部分
                    tempChunkSize = (alreadySubmittedBytes + processing) - offset;
                } else {
                    tempChunkSize = Math.min(chunkSize, (alreadySubmittedBytes + processing) - offset);
                }
                
                if (tempChunkSize <= 0) { // 如果計算出的大小為0或負，則跳過
                    continue;
                }

                pool.submit(new ChunkSender(offset, tempChunkSize));
            }
            alreadySubmittedBytes += processing;
        }

        pool.shutdown();
        if (!pool.awaitTermination(10000, TimeUnit.MINUTES)) { // Timeout is very long
            pool.shutdownNow();
        }
    }

    private class ChunkSender implements Runnable {
        private final long offset;
        private final long length; // length of the chunk this sender is responsible for

        public ChunkSender(long offset, long length) {
            this.offset = offset;
            this.length = length;
        }

        @Override
        public void run() {
            // Check if there's anything to send for this chunk
            if (length <= 0) {
                // System.out.println("ChunkSender for offset=" + offset + " has zero length, skipping.");
                return;
            }

            try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
                 SocketChannel socketChannel = SocketChannel.open()) {

                socketChannel.connect(new InetSocketAddress(host, port));

                // 1. 傳送 Header (offset: long, length: long)
                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES);
                headerBuffer.putLong(offset);
                headerBuffer.putLong(length);
                headerBuffer.flip();
                while (headerBuffer.hasRemaining()) {
                    socketChannel.write(headerBuffer);
                }

                // 2. 使用 transferTo 傳送檔案區塊數據
                long bytesTransferredForThisChunk = 0;
                while (bytesTransferredForThisChunk < length) {
                    // fileChannel.transferTo(position, count, target)
                    // position: 檔案中的絕對位置
                    // count: 要傳輸的位元組數
                    long transferredThisCall = fileChannel.transferTo(
                            offset + bytesTransferredForThisChunk, // Absolute position in the file
                            length - bytesTransferredForThisChunk,  // Remaining bytes for this chunk
                            socketChannel
                    );

                    if (transferredThisCall < 0) {
                        // 根據 SocketChannel javadoc, write (which transferTo uses)
                        // should not return -1 unless channel is closed.
                        throw new IOException("SocketChannel closed or error during transferTo, returned " + transferredThisCall);
                    }
                    
                    bytesTransferredForThisChunk += transferredThisCall;
                    if (callback != null && transferredThisCall > 0) {
                        callback.onProgress(transferredThisCall);
                    }
                    // If transferredThisCall is 0, the loop will continue,
                    // which is correct for blocking channels if the buffer was temporarily full
                    // (though less common for transferTo with blocking socket).
                }
                // System.out.printf("已傳送分段 (NIO)：offset=%d, length=%d%n", offset, length);

            } catch (IOException e) {
                System.err.println("ChunkSender (NIO) 發生 IO 錯誤 (offset=" + offset + ", length=" + length + "): " + e.getMessage());
                e.printStackTrace();
                if (callback != null) {
                    // callback.onError(e); // 假設您的 callback 有 onError 方法
                }
            } catch (Exception e) { // 捕捉其他未預期錯誤
                System.err.println("ChunkSender (NIO) 發生未預期錯誤 (offset=" + offset + ", length=" + length + "): " + e.getMessage());
                e.printStackTrace();
                 if (callback != null) {
                    // callback.onError(e);
                }
            }
        }
    }
}
