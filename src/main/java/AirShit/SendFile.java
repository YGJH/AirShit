package AirShit;

import AirShit.ui.LogPanel; // Assuming LogPanel is accessible

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicInteger;

public class SendFile {
    private final String host;
    private final int port;
    private final File file;
    private final TransferCallback callback;
    private int threadCount;

    public SendFile(String host, int port, File file, int threadCount, TransferCallback callback) {
        this.callback = callback;
        this.host = host;
        this.port = port;
        this.file = file;
        // Log the passed threadCount before Math.max
        LogPanel.log("SendFile Constructor: Received threadCount=" + threadCount);
        this.threadCount = Math.max(1, threadCount); // Ensure at least one thread
        LogPanel.log("SendFile Constructor: Set SendFile.this.threadCount=" + this.threadCount);
    }

    public void start() throws IOException, InterruptedException {
        long fileLength = file.length();
        if (fileLength == 0) {
            LogPanel.log("SendFile: Source file is empty. Attempting to send zero-byte file signal.");
            // Special handling for zero-byte files: connect, send zero-length header, close.
            // This requires receiver to handle a zero-length chunk correctly.
            // For simplicity with persistent connections, we might need a different signal
            // or ensure at least one worker tries to send a "zero-length" chunk if file is empty.
            // For now, if file is empty, no chunks will be added to queue, workers will do nothing.
            // The receiver side needs to be aware of this.
            // A robust way: if fileLength is 0, send a single chunk with offset 0, length 0.
        }

        LogPanel.log("SendFile starting. File size: " + fileLength + ", Threads: " + threadCount);

        ConcurrentLinkedQueue<ChunkInfo> chunkQueue = new ConcurrentLinkedQueue<>();
        populateChunkQueue(fileLength, chunkQueue);

        if (fileLength > 0 && chunkQueue.isEmpty()) {
            LogPanel.log("Error: File length is " + fileLength + " but no chunks were generated.");
            if (callback != null) callback.onError(new IOException("No chunks generated for non-empty file."));
            return;
        }
        if (fileLength == 0 && chunkQueue.isEmpty()) { // Add a zero-length chunk for empty files
            chunkQueue.offer(new ChunkInfo(0,0));
            LogPanel.log("SendFile: Added a zero-length chunk for empty file.");
        }


        List<SocketChannel> channels = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger activeWorkers = new AtomicInteger(threadCount);


        try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            for (int i = 0; i < threadCount; i++) {
                SocketChannel socketChannel = SocketChannel.open();
                socketChannel.configureBlocking(true); // Ensure blocking mode for simplicity here
                try {
                    socketChannel.connect(new InetSocketAddress(host, port));
                    channels.add(socketChannel);
                    LogPanel.log("SendFile: Worker " + i + " connected to " + host + ":" + port); // Already there
                    pool.submit(new SenderWorker(socketChannel, fileChannel, chunkQueue, callback, activeWorkers));
                } catch (IOException e) {
                    LogPanel.log("SendFile: Worker " + i + " failed to connect or start: " + e.getMessage());
                    // Close this specific channel if it was opened
                    if (socketChannel.isOpen()) socketChannel.close();
                    activeWorkers.decrementAndGet(); // This worker won't run
                    // If any connection fails, we might want to abort the whole process
                    // For now, other workers will proceed. This could lead to partial sends.
                    // A more robust solution would be to signal all other workers to stop or not start.
                    // And then clean up all successfully opened channels.
                    // For this example, we'll let it try with fewer workers if some fail to connect.
                }
            }
                LogPanel.log("SendFile: Number of successfully connected sender workers: " + channels.size()); // ADD THIS
            if (channels.isEmpty() && fileLength > 0) {
                LogPanel.log("SendFile: No sender workers could connect. Aborting.");
                if (callback != null) callback.onError(new IOException("All sender workers failed to connect."));
                pool.shutdownNow();
                return;
            }


            pool.shutdown();
            // Wait for all tasks to complete or timeout
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) { // Long timeout
                LogPanel.log("SendFile: Pool termination timeout. Forcing shutdown.");
                pool.shutdownNow();
                if (callback != null) callback.onError(new IOException("SendFile tasks timed out."));
            } else {
                LogPanel.log("SendFile: All sender workers completed.");
                // At this point, onComplete should be triggered by the receiver's logic
                // or after verifying with receiver. For now, sender assumes its job is done.
            }

        } finally {
            for (SocketChannel sc : channels) {
                if (sc.isOpen()) {
                    try {
                        sc.close();
                    } catch (IOException e) {
                        LogPanel.log("SendFile: Error closing a sender socket channel: " + e.getMessage());
                    }
                }
            }
            LogPanel.log("SendFile: All sender socket channels closed.");
        }
    }

    private void populateChunkQueue(long fileLength, ConcurrentLinkedQueue<ChunkInfo> chunkQueue) {
        if (fileLength == 0) {
            // Handled by start() which adds a (0,0) chunk if queue is empty
            return;
        }
        LogPanel.log("SendFile.populateChunkQueue: Calculating chunks for fileLength=" + fileLength + ", SendFile.this.threadCount=" + this.threadCount);

        if (this.threadCount <= 0) { // Should have been caught by Math.max(1, threadCount) in constructor
            LogPanel.log("SendFile.populateChunkQueue: Error - threadCount is " + this.threadCount + ". Defaulting to 1 chunk.");
            chunkQueue.offer(new ChunkInfo(0, fileLength));
        } else if (this.threadCount == 1) {
            ChunkInfo singleChunk = new ChunkInfo(0, fileLength);
            LogPanel.log("SendFile.populateChunkQueue: Single thread mode (this.threadCount=1), adding one chunk: " + singleChunk);
            chunkQueue.offer(singleChunk);
        } else {
            // Multi-threaded chunking
            long chunkSize = (fileLength + this.threadCount - 1) / this.threadCount; // Ceiling division
            for (int i = 0; i < this.threadCount; i++) {
                long offset = i * chunkSize;
                if (offset >= fileLength) {
                    break; 
                }
                long length = Math.min(chunkSize, fileLength - offset);
                if (length > 0) {
                    ChunkInfo chunk = new ChunkInfo(offset, length);
                    LogPanel.log("SendFile.populateChunkQueue: Adding chunk " + (i+1) + "/" + this.threadCount + ": " + chunk);
                    chunkQueue.offer(chunk); 
                }
            }
        }
        LogPanel.log("SendFile.populateChunkQueue: Finished. Final chunk queue size: " + chunkQueue.size());
    }

    private static class SenderWorker implements Runnable {
        private final SocketChannel socketChannel;
        private final FileChannel fileChannel; // Shared
        private final ConcurrentLinkedQueue<ChunkInfo> chunkQueue;
        private final TransferCallback callback;
        private final AtomicInteger activeWorkers;


        public SenderWorker(SocketChannel socketChannel, FileChannel fileChannel,
                              ConcurrentLinkedQueue<ChunkInfo> chunkQueue, TransferCallback callback, AtomicInteger activeWorkers) {
            this.socketChannel = socketChannel;
            this.fileChannel = fileChannel;
            this.chunkQueue = chunkQueue;
            this.callback = callback;
            this.activeWorkers = activeWorkers;
        }

        @Override
        public void run() {
            LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + ") started for socket: " + socketChannel.socket().getLocalPort() + " -> " + socketChannel.socket().getRemoteSocketAddress());
            try {
                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES);
                ChunkInfo chunk;
                while ((chunk = chunkQueue.poll()) != null) {
                    LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + "): Polled chunk " + chunk + " from queue. Remaining in queue (approx): " + chunkQueue.size());
                    if (chunk.length < 0) {
                        LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + "): Skipped invalid chunk with negative length: " + chunk);
                        continue;
                    }

                    headerBuffer.clear();
                    headerBuffer.putLong(chunk.offset);
                    headerBuffer.putLong(chunk.length);
                    headerBuffer.flip();
                    LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + "): Sending header for " + chunk);
                    while (headerBuffer.hasRemaining()) {
                        socketChannel.write(headerBuffer);
                    }
                    
                    if (chunk.length == 0) {
                        LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + "): Sent zero-length chunk header for " + chunk + ". No data to send.");
                        continue; 
                    }

                    LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + "): Sending data for " + chunk);
                    long bytesTransferredForThisChunk = 0;
                    while (bytesTransferredForThisChunk < chunk.length) {
                        long transferredThisCall = fileChannel.transferTo(
                                chunk.offset + bytesTransferredForThisChunk,
                                chunk.length - bytesTransferredForThisChunk,
                                socketChannel
                        );
                        if (transferredThisCall < 0) {
                            throw new IOException("SocketChannel closed or error during transferTo, returned " + transferredThisCall);
                        }
                        bytesTransferredForThisChunk += transferredThisCall;
                        if (callback != null && transferredThisCall > 0) {
                            callback.onProgress(transferredThisCall);
                        }
                    }
                    LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + "): Finished sending data for chunk " + chunk + ". Total sent for this chunk: " + bytesTransferredForThisChunk);
                }
                LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + "): No more chunks in queue. Worker finishing.");
            } catch (IOException e) {
                LogPanel.log("Error in SenderWorker (" + Thread.currentThread().getName() + "): " + e.getMessage());
                if (callback != null) {
                    callback.onError(e); // Report error
                }
            } finally {
                LogPanel.log("SenderWorker (" + Thread.currentThread().getName() + ") run method finished.");
            }
        }
    }
}
