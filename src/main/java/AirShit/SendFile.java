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
        this.threadCount = Math.max(1, threadCount);
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
        TransferCallback workerCallback = getWrappedCallback(); 

        LogPanel.log("SendFile starting. File: " + file.getName() + ", Size: " + fileLength + ", Threads: " + threadCount);

        ConcurrentLinkedQueue<ChunkInfo> chunkQueue = new ConcurrentLinkedQueue<>();
        populateChunkQueue(fileLength, chunkQueue);

        if (fileLength > 0 && chunkQueue.isEmpty()) {
            LogPanel.log("Error: File length is " + fileLength + " but no chunks were generated.");
            if (workerCallback != null) workerCallback.onError(new IOException("No chunks generated for non-empty file."));
            return;
        }
        if (fileLength == 0 && chunkQueue.isEmpty()) {
            chunkQueue.offer(new ChunkInfo(0, 0));
            LogPanel.log("SendFile: Added a zero-length chunk for empty file.");
        }
        if (chunkQueue.isEmpty()){ 
             LogPanel.log("Error: Chunk queue is empty before starting workers.");
             if (workerCallback != null) workerCallback.onError(new IOException("Chunk queue empty unexpectedly."));
             return;
        }

        List<SocketChannel> channels = new ArrayList<>();
        int poolSize = Math.min(this.threadCount, chunkQueue.size());
        if (fileLength == 0) poolSize = 1; 
        poolSize = Math.max(1, poolSize); 

        LogPanel.log("SendFile: Effective pool size for sender workers: " + poolSize);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        AtomicInteger workersToStart = new AtomicInteger(poolSize); // This seems unused, can be removed if not needed by SenderWorker logic

        try (FileChannel fileChannel = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            for (int i = 0; i < poolSize; i++) {
                SocketChannel socketChannel = null; 
                try {
                    socketChannel = SocketChannel.open();
                    socketChannel.configureBlocking(true);
                    LogPanel.log("SendFile: Worker " + i + " attempting to connect to " + host + ":" + port);
                    socketChannel.connect(new InetSocketAddress(host, port));
                    channels.add(socketChannel);
                    LogPanel.log("SendFile: Worker " + i + " connected to " + host + ":" + port + " (Local: " + socketChannel.getLocalAddress() + ")");
                    pool.submit(new SenderWorker(socketChannel, fileChannel, chunkQueue, workerCallback, workersToStart));
                } catch (IOException e) {
                    LogPanel.log("SendFile: Worker " + i + " failed to connect or start: " + e.getMessage());
                    if (socketChannel != null && socketChannel.isOpen()) {
                        try { socketChannel.close(); } catch (IOException sce) { LogPanel.log("Error closing failed socket channel: " + sce.getMessage());}
                    }
                    // workersToStart.decrementAndGet(); // Not strictly necessary if not used for completion logic
                    if (workerCallback != null) workerCallback.onError(new IOException("Worker " + i + " connection failed: " + e.getMessage(), e));
                }
            }
            LogPanel.log("SendFile: Number of successfully initiated sender workers: " + channels.size() + " (Expected to start: " + poolSize + ")");

            if (channels.isEmpty() && fileLength > 0) { 
                LogPanel.log("SendFile: No sender workers could connect. Aborting.");
                pool.shutdownNow(); 
                return;
            }
            if (channels.isEmpty() && fileLength == 0 && poolSize > 0) { 
                 LogPanel.log("SendFile: No sender worker connected for zero-byte file. Aborting.");
                 pool.shutdownNow();
                 return;
            }

            pool.shutdown();
            LogPanel.log("SendFile: Pool shutdown initiated. Waiting for termination...");
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) {
                LogPanel.log("SendFile: Pool termination timeout. Forcing shutdown.");
                pool.shutdownNow();
                if (workerCallback != null && !errorReportedByWorker.get()) workerCallback.onError(new IOException("SendFile tasks timed out."));
            } else {
                LogPanel.log("SendFile: All sender worker tasks have completed execution.");
                if (!chunkQueue.isEmpty() && !errorReportedByWorker.get()) {
                    LogPanel.log("Warning: SendFile workers finished, but chunk queue is not empty. Size: " + chunkQueue.size());
                    if (workerCallback != null) { 
                         workerCallback.onError(new IOException("Transfer incomplete: chunks remaining after workers finished."));
                    }
                } else if (!errorReportedByWorker.get()) {
                    LogPanel.log("SendFile: All data sent without worker-reported errors and queue empty. Calling onComplete.");
                    if (originalCallback != null) originalCallback.onComplete(); 
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
            LogPanel.log("SendFile: All sender socket channels attempted to close.");
            if (!pool.isTerminated()) {
                LogPanel.log("SendFile: Forcing pool shutdown in final finally block.");
                pool.shutdownNow();
            }
        }
    }

    private void populateChunkQueue(long fileLength, ConcurrentLinkedQueue<ChunkInfo> chunkQueue) {
        if (fileLength == 0) {
            return;
        }
        LogPanel.log("SendFile.populateChunkQueue: Calculating chunks for fileLength=" + fileLength + ", SendFile.this.threadCount=" + this.threadCount);

        if (this.threadCount <= 0) {
            LogPanel.log("SendFile.populateChunkQueue: Error - threadCount is " + this.threadCount + ". Defaulting to 1 chunk.");
            chunkQueue.offer(new ChunkInfo(0, fileLength));
        } else if (this.threadCount == 1 || fileLength < (1024 * 10)) { 
            ChunkInfo singleChunk = new ChunkInfo(0, fileLength);
            LogPanel.log("SendFile.populateChunkQueue: Single chunk mode (threadCount=" + this.threadCount + ", fileLength=" + fileLength + "), adding one chunk: " + singleChunk);
            chunkQueue.offer(singleChunk);
        } else {
            long idealChunkSize = (fileLength + this.threadCount - 1) / this.threadCount; 
            long minChunkSize = 64 * 1024;
            long actualChunkSize = Math.max(idealChunkSize, minChunkSize);
            if (actualChunkSize >= fileLength && fileLength > 0) { 
                 chunkQueue.offer(new ChunkInfo(0, fileLength));
                 LogPanel.log("SendFile.populateChunkQueue: Calculated chunk size covers whole file. Adding one chunk: " + new ChunkInfo(0, fileLength));
            } else {
                int numChunks = 0;
                for (long offset = 0; offset < fileLength; offset += actualChunkSize) {
                    long length = Math.min(actualChunkSize, fileLength - offset);
                    if (length > 0) {
                        ChunkInfo chunk = new ChunkInfo(offset, length);
                        LogPanel.log("SendFile.populateChunkQueue: Adding chunk " + (numChunks + 1) + ": " + chunk);
                        chunkQueue.offer(chunk);
                        numChunks++;
                    } else {
                        break; 
                    }
                }
                 LogPanel.log("SendFile.populateChunkQueue: Generated " + numChunks + " chunks with target size " + actualChunkSize);
            }
        }
        LogPanel.log("SendFile.populateChunkQueue: Finished. Final chunk queue size: " + chunkQueue.size());
    }

    private static class SenderWorker implements Runnable {
        private final SocketChannel socketChannel;
        private final FileChannel fileChannel;
        private final ConcurrentLinkedQueue<ChunkInfo> chunkQueue;
        private final TransferCallback callback; 
        private final AtomicInteger activeWorkersToken; 

        public SenderWorker(SocketChannel socketChannel, FileChannel fileChannel,
                              ConcurrentLinkedQueue<ChunkInfo> chunkQueue, TransferCallback callback, AtomicInteger activeWorkersToken) {
            this.socketChannel = socketChannel;
            this.fileChannel = fileChannel;
            this.chunkQueue = chunkQueue;
            this.callback = callback;
            this.activeWorkersToken = activeWorkersToken;
        }

        @Override
        public void run() {
            String workerName = Thread.currentThread().getName();
            LogPanel.log("SenderWorker (" + workerName + ") started for socket: " + socketChannel.socket().getLocalPort() + " -> " + socketChannel.socket().getRemoteSocketAddress());
            try {
                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES); 
                ChunkInfo chunk;
                boolean processedAtLeastOneChunk = false;

                while ((chunk = chunkQueue.poll()) != null) {
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
                        if (System.currentTimeMillis() - loopStartTime > 300000) { 
                            throw new IOException("Timeout sending data for chunk " + chunk);
                        }
                        long transferredThisCall = fileChannel.transferTo(
                                chunk.offset + bytesTransferredForThisChunk,
                                chunk.length - bytesTransferredForThisChunk,
                                socketChannel
                        );
                        if (transferredThisCall <= 0 && (chunk.length - bytesTransferredForThisChunk > 0)) {
                             Thread.sleep(50); 
                            //  LogPanel.log("SenderWorker (" + workerName + "): transferTo returned " + transferredThisCall + " for chunk " + chunk + ". Retrying or may indicate issue.");
                        }
                        bytesTransferredForThisChunk += transferredThisCall;
                        if (callback != null && transferredThisCall > 0) {
                            callback.onProgress(transferredThisCall);
                        }
                    }
                    // LogPanel.log("SenderWorker (" + workerName + "): Finished sending data for chunk " + chunk + ". Total sent: " + bytesTransferredForThisChunk);
                }

                if (!processedAtLeastOneChunk) {
                    LogPanel.log("SenderWorker (" + workerName + "): Polled no chunks from queue. This might be normal if other workers took all chunks or if queue was empty for this worker.");
                }
                LogPanel.log("SenderWorker (" + workerName + "): No more chunks in queue for this worker. Worker finishing.");

            } catch (IOException e) {
                LogPanel.log("Error in SenderWorker (" + workerName + "): " + e.getClass().getSimpleName() + " - " + e.getMessage());
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
                // activeWorkersToken.decrementAndGet(); // This token is not strictly necessary for completion tracking here
                LogPanel.log("SenderWorker (" + workerName + ") run method finished.");
            } // End of finally
        } // End of run() method
    } // End of SenderWorker class

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
} // End of SendFile class
