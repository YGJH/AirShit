package AirShit;

import AirShit.ui.LogPanel;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SendFile {
    private final String host;
    private final int port;
    private final File file;
    private final TransferCallback originalCallback;
    private int threadCount;
    private final AtomicBoolean errorReportedByWorker = new AtomicBoolean(false);

    public SendFile(String host, int port, File file, int threadCount, TransferCallback callback) {
        this.originalCallback = callback;
        this.host = host;
        this.port = port;
        this.file = file;
        LogPanel.log("SendFile Constructor: Received threadCount=" + threadCount);
        this.threadCount = Math.max(1, threadCount); // Ensure at least one thread
        LogPanel.log("SendFile Constructor: Set SendFile.this.threadCount=" + this.threadCount);
    }

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
            public void onComplete() {
                // This will be called by SendFile itself after all workers are done
                // and no errors were reported by them.
            }

            @Override
            public void onError(Exception e) {
                // If any worker calls onError, we mark it.
                // The main SendFile logic will decide if onComplete on originalCallback is called.
                errorReportedByWorker.set(true);
                if (originalCallback != null) originalCallback.onError(e);
            }
        };
    }

    public void start() throws IOException, InterruptedException {
        long fileLength = file.length();
        TransferCallback workerCallback = getWrappedCallback(); // Use this for workers

        LogPanel.log("SendFile starting. File: " + file.getName() + ", Size: " + fileLength + ", Threads: " + threadCount);

        ConcurrentLinkedQueue<ChunkInfo> chunkQueue = new ConcurrentLinkedQueue<>();
        populateChunkQueue(fileLength, chunkQueue);

        if (fileLength > 0 && chunkQueue.isEmpty()) {
            LogPanel.log("Error: File length is " + fileLength + " but no chunks were generated.");
            if (workerCallback != null) workerCallback.onError(new IOException("No chunks generated for non-empty file."));
            return;
        }
        if (fileLength == 0 && chunkQueue.isEmpty()) {
            // Ensure a (0,0) chunk for zero-byte files if populateChunkQueue didn't add it
            chunkQueue.offer(new ChunkInfo(0, 0));
            // LogPanel.log("SendFile: Added a zero-length chunk for empty file.");
        }
        if (chunkQueue.isEmpty() && fileLength > 0){ // Should not happen if logic above is correct
             LogPanel.log("Error: Chunk queue is empty before starting workers for a non-empty file.");
             if (workerCallback != null) workerCallback.onError(new IOException("Chunk queue empty unexpectedly for non-empty file."));
             return;
        }


        List<SocketChannel> channels = new ArrayList<>();
        // Determine pool size: min of configured threads and available chunks, but at least 1.
        int poolSize = chunkQueue.isEmpty() ? 1 : Math.min(this.threadCount, chunkQueue.size()); // if queue is empty (e.g. 0-byte file), still need 1 worker for the (0,0) chunk
        if (fileLength == 0) poolSize = 1; // Specifically for zero-byte file, one worker.
        poolSize = Math.max(1, poolSize); // Ensure at least one thread in pool.

        LogPanel.log("SendFile: Effective pool size for sender workers: " + poolSize);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        // AtomicInteger workersToStart = new AtomicInteger(poolSize); // This seems unused

        try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            for (int i = 0; i < poolSize; i++) {
                SocketChannel socketChannel = null;
                try {
                    socketChannel = SocketChannel.open();
                    socketChannel.configureBlocking(true); // Using blocking for simplicity here
                    // LogPanel.log("SendFile: Worker " + i + " attempting to connect to " + host + ":" + port);
                    socketChannel.connect(new InetSocketAddress(host, port));
                    channels.add(socketChannel);
                    // LogPanel.log("SendFile: Worker " + i + " connected to " + host + ":" + port + " (Local: " + socketChannel.getLocalAddress() + ")");
                    pool.submit(new SenderWorker(socketChannel, fileChannel, chunkQueue, workerCallback /*, workersToStart*/));
                } catch (IOException e) {
                    LogPanel.log("SendFile: Worker " + i + " failed to connect or start: " + e.getMessage());
                    if (socketChannel != null && socketChannel.isOpen()) {
                        try { socketChannel.close(); } catch (IOException sce) { LogPanel.log("Error closing failed socket channel: " + sce.getMessage());}
                    }
                    // workersToStart.decrementAndGet();
                    if (workerCallback != null) workerCallback.onError(new IOException("Worker " + i + " connection failed: " + e.getMessage(), e));
                    // If a worker fails to connect, errorReportedByWorker will be set.
                }
            }
            // LogPanel.log("SendFile: Number of successfully initiated sender workers: " + channels.size() + " (Expected to start: " + poolSize + ")");

            if (channels.isEmpty() && (fileLength > 0 || poolSize > 0)) { // No workers connected
                LogPanel.log("SendFile: No sender workers could connect. Aborting.");
                // workerCallback.onError would have been called for each failed connection
                pool.shutdownNow(); // Ensure pool is shutdown
                // errorReportedByWorker should be true, so onComplete won't be called by logic below.
                return;
            }

            pool.shutdown();
            // LogPanel.log("SendFile: Pool shutdown initiated. Waiting for termination...");
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
                LogPanel.log("SendFile: Pool termination timeout. Forcing shutdown.");
                pool.shutdownNow();
                // If timed out, and no worker previously reported an error, report this timeout as an error.
                if (workerCallback != null && !errorReportedByWorker.get()) {
                    workerCallback.onError(new IOException("SendFile tasks timed out."));
                }
            } else {
                // LogPanel.log("SendFile: All sender worker tasks have completed execution (pool terminated).");
                // Check if all chunks were processed and no worker reported an error
                if (!chunkQueue.isEmpty() && !errorReportedByWorker.get()) {
                    LogPanel.log("Warning: SendFile workers finished, but chunk queue is not empty. Size: " + chunkQueue.size());
                    if (workerCallback != null) {
                         workerCallback.onError(new IOException("Transfer incomplete: chunks remaining after workers finished."));
                    }
                } else if (!errorReportedByWorker.get()) {
                    LogPanel.log("SendFile: All data sent without worker-reported errors and queue empty. Calling onComplete.");
                    if (originalCallback != null) originalCallback.onComplete(); // Call original onComplete
                } else {
                    LogPanel.log("SendFile: Pool terminated, but one or more workers reported an error or chunks remain. onComplete not called by SendFile.");
                }
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
            // LogPanel.log("SendFile: All sender socket channels attempted to close.");
            if (!pool.isTerminated()) {
                LogPanel.log("SendFile: Forcing pool shutdown in final finally block.");
                pool.shutdownNow();
            }
        }
    }

    private void populateChunkQueue(long fileLength, ConcurrentLinkedQueue<ChunkInfo> chunkQueue) {
        if (fileLength == 0) {
            // For zero-byte files, SendFile.start() adds a single (0,0) chunk.
            LogPanel.log("SendFile.populateChunkQueue: fileLength is 0. Single (0,0) chunk will be handled by SendFile.start().");
            return;
        }
        LogPanel.log("SendFile.populateChunkQueue: Calculating chunks for fileLength=" + fileLength + ", SendFile.this.threadCount=" + this.threadCount);

        if (this.threadCount <= 0) { // Should have been corrected by constructor, but defensive.
            LogPanel.log("SendFile.populateChunkQueue: Error - threadCount is " + this.threadCount + ". Defaulting to 1 chunk.");
            chunkQueue.offer(new ChunkInfo(0, fileLength));
            LogPanel.log("SendFile.populateChunkQueue: Finished. Final chunk queue size: " + chunkQueue.size());
            return;
        }
        
        // Single chunk for very small files or if threadCount is 1
        if (this.threadCount == 1 || fileLength < (1024 * 10)) { // e.g. < 10KB
            ChunkInfo singleChunk = new ChunkInfo(0, fileLength);
            // LogPanel.log("SendFile.populateChunkQueue: Single chunk mode (threadCount=" + this.threadCount + ", fileLength=" + fileLength + "), adding one chunk: " + singleChunk);
            chunkQueue.offer(singleChunk);
        } else {
            // Multi-threaded chunking
            long idealChunkSize = (fileLength + this.threadCount - 1) / this.threadCount; // Ceiling division
            long minChunkSize = 64 * 1024; // Minimum practical chunk size (e.g., 64KB)
            long actualChunkSize = Math.max(idealChunkSize, minChunkSize);

            // If calculated chunk size is still >= file length (e.g., file is smaller than minChunkSize but > 10KB), send as one chunk.
            if (actualChunkSize >= fileLength) {
                 chunkQueue.offer(new ChunkInfo(0, fileLength));
                //  LogPanel.log("SendFile.populateChunkQueue: Calculated chunk size ("+actualChunkSize+") covers whole file ("+fileLength+"). Adding one chunk: " + new ChunkInfo(0, fileLength));
            } else {
                int numChunks = 0;
                for (long offset = 0; offset < fileLength; offset += actualChunkSize) {
                    long length = Math.min(actualChunkSize, fileLength - offset);
                    if (length > 0) { // Ensure we don't add a zero-length chunk here unless it's the only one for a zero-byte file (handled above)
                        ChunkInfo chunk = new ChunkInfo(offset, length);
                        // LogPanel.log("SendFile.populateChunkQueue: Adding chunk " + (numChunks + 1) + ": " + chunk);
                        chunkQueue.offer(chunk);
                        numChunks++;
                    } else if (offset < fileLength) { // Should not happen if fileLength > 0
                        LogPanel.log("SendFile.populateChunkQueue: Warning - calculated zero length for chunk at offset " + offset + " while fileLength is " + fileLength);
                    }
                }
                 LogPanel.log("SendFile.populateChunkQueue: Generated " + numChunks + " chunks with target size " + actualChunkSize);
            }
        }
        // LogPanel.log("SendFile.populateChunkQueue: Finished. Final chunk queue size: " + chunkQueue.size());
    }

    private static class SenderWorker implements Runnable {
        private final SocketChannel socketChannel;
        private final FileChannel fileChannel;
        private final ConcurrentLinkedQueue<ChunkInfo> chunkQueue;
        private final TransferCallback callback; // This is the wrapped callback

        public SenderWorker(SocketChannel socketChannel, FileChannel fileChannel,
                              ConcurrentLinkedQueue<ChunkInfo> chunkQueue, TransferCallback callback) {
            this.socketChannel = socketChannel;
            this.fileChannel = fileChannel;
            this.chunkQueue = chunkQueue;
            this.callback = callback;
        }

        @Override
        public void run() {
            String workerName = Thread.currentThread().getName();
            LogPanel.log("SenderWorker (" + workerName + ") started for socket: " + 
                         (socketChannel.isOpen() ? socketChannel.socket().getLocalPort() + " -> " + socketChannel.socket().getRemoteSocketAddress() : "already closed"));
            
            if (!socketChannel.isOpen() || !socketChannel.isConnected()) {
                LogPanel.log("Error in SenderWorker (" + workerName + "): SocketChannel is not open or not connected at the start.");
                if (callback != null) {
                    callback.onError(new IOException("SocketChannel not open/connected at worker start for " + workerName));
                }
                return;
            }

            try {
                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES); // For offset and length
                ChunkInfo chunk;
                boolean processedAtLeastOneChunk = false;

                while ((chunk = chunkQueue.poll()) != null) {
                    if (!socketChannel.isOpen()) {
                        throw new IOException("SocketChannel closed before processing chunk " + chunk + " on worker " + workerName);
                    }
                    processedAtLeastOneChunk = true;
                    LogPanel.log("SenderWorker (" + workerName + "): Polled chunk " + chunk + ". Queue size approx: " + chunkQueue.size());
                    if (chunk.length < 0) {
                        LogPanel.log("SenderWorker (" + workerName + "): Skipped invalid chunk with negative length: " + chunk);
                        continue;
                    }

                    headerBuffer.clear();
                    headerBuffer.putLong(chunk.offset);
                    headerBuffer.putLong(chunk.length);
                    headerBuffer.flip();
                    LogPanel.log("SenderWorker (" + workerName + "): Sending header for " + chunk);
                    while (headerBuffer.hasRemaining()) {
                        if (!socketChannel.isOpen()) throw new IOException("SocketChannel closed while sending header for chunk " + chunk);
                        socketChannel.write(headerBuffer);
                    }

                    if (chunk.length == 0) {
                        LogPanel.log("SenderWorker (" + workerName + "): Sent zero-length chunk header for " + chunk + ". No data body to send.");
                        continue;
                    }

                    LogPanel.log("SenderWorker (" + workerName + "): Sending data for " + chunk);
                    long bytesTransferredForThisChunk = 0;
                    long loopStartTime = System.currentTimeMillis(); 

                    while (bytesTransferredForThisChunk < chunk.length) {
                        if (!socketChannel.isOpen()) {
                            throw new IOException("SocketChannel closed while sending data for chunk " + chunk + " (sent " + bytesTransferredForThisChunk + "/" + chunk.length + ") on worker " + workerName);
                        }
                        // if (System.currentTimeMillis() - loopStartTime > 300000) { // 5 minutes per chunk data send loop
                        if (System.currentTimeMillis() - loopStartTime > 30 * 60 * 1000) { // 30 minutes per chunk data send loop
                            throw new IOException("Timeout sending data for chunk " + chunk + " on worker " + workerName + ". Sent " + bytesTransferredForThisChunk + "/" + chunk.length);
                        }

                        long transferredThisCall = fileChannel.transferTo(
                                chunk.offset + bytesTransferredForThisChunk,
                                chunk.length - bytesTransferredForThisChunk,
                                socketChannel
                        );

                        if (transferredThisCall == 0 && (chunk.length - bytesTransferredForThisChunk > 0)) {
                             // If transferTo returns 0, the socket buffer might be full.
                             // A very short sleep can prevent a tight loop.
                             // If the connection is dead, an IOException should occur on a subsequent attempt or timeout.
                             LogPanel.log("SenderWorker (" + workerName + "): transferTo returned 0 for chunk " + chunk + " with " + (chunk.length - bytesTransferredForThisChunk) + " bytes remaining. Socket buffer might be full. Sleeping briefly.");
                             Thread.sleep(20); // Reduced sleep, more frequent checks.
                        } else if (transferredThisCall < 0) {
                            // transferTo returning a negative value is unusual and typically indicates an error or EOF on the source channel (not expected for a file).
                            throw new IOException("fileChannel.transferTo returned " + transferredThisCall + " for chunk " + chunk + " on worker " + workerName);
                        }
                        
                        bytesTransferredForThisChunk += transferredThisCall;
                        if (callback != null && transferredThisCall > 0) {
                            callback.onProgress(transferredThisCall);
                        }
                    }
                    LogPanel.log("SenderWorker (" + workerName + "): Finished sending data for chunk " + chunk + ". Total sent for this chunk: " + bytesTransferredForThisChunk);
                }

                if (!processedAtLeastOneChunk) {
                    LogPanel.log("SenderWorker (" + workerName + "): Did not process any chunks. Queue empty or worker started late. This is normal if other workers handled all chunks.");
                }
                LogPanel.log("SenderWorker (" + workerName + "): No more chunks in queue for this worker, or worker did not process any. Worker finishing.");

            } catch (IOException e) {
                // Check if the exception is due to the channel being closed, which is common if the receiver aborts.
                String socketState = "unknown";
                if (socketChannel != null) {
                    socketState = "isOpen=" + socketChannel.isOpen() + ", isConnected=" + socketChannel.isConnected() + 
                                  (socketChannel.socket() != null ? ", remoteAddr=" + socketChannel.socket().getRemoteSocketAddress() : "");
                }
                LogPanel.log("Error in SenderWorker (" + workerName + "): " + e.getClass().getSimpleName() + " - " + e.getMessage() + ". Socket state: " + socketState);
                // e.printStackTrace(); 
                if (callback != null) {
                    callback.onError(e); 
                }
            } catch (InterruptedException e) {
                LogPanel.log("SenderWorker (" + workerName + ") was interrupted: " + e.getMessage());
                Thread.currentThread().interrupt(); 
                if (callback != null) {
                    callback.onError(e);
                }
            } finally {
                LogPanel.log("SenderWorker (" + workerName + ") run method finished.");
            }
        }
    }

    private static class ChunkInfo {
        final long offset;
        final long length;

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
