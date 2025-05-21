package AirShit;

import AirShit.ui.LogPanel; // Assuming LogPanel is accessible

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Receiver {
    private final ServerSocket serverSocket; // This is the ServerSocket passed from FileReceiver

    public Receiver(ServerSocket ss) {
        this.serverSocket = ss;
    }

    public boolean start(String outputFile,
                         long fileLength,
                         int threadCount, // This is the negotiated thread count
                         TransferCallback cb) throws IOException, InterruptedException {

        File out = new File(outputFile);
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        if (out.exists()) {
            out.delete();
        }
        
        if (fileLength == 0) {
            LogPanel.log("Receiver: Expecting a zero-byte file: " + outputFile);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                // Empty file created
            }
        }

        LogPanel.log("Receiver starting. Expecting file: " + outputFile + ", Size: " + fileLength + ", Connections: " + threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        List<Socket> dataSockets = new ArrayList<>();

        AtomicLong totalBytesActuallyReceived = new AtomicLong(0);

        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            // Optional: Pre-allocate space. Can be slow.
            // if (fileLength > 0) {
            //    raf.setLength(fileLength);
            // }

            for (int i = 0; i < threadCount; i++) {
                Socket dataSock = null;
                try {
                    LogPanel.log("Receiver: Worker " + i + " waiting to accept connection on " + serverSocket.getLocalSocketAddress() + "...");
                    dataSock = serverSocket.accept(); 
                    dataSockets.add(dataSock);
                    LogPanel.log("Receiver: Worker " + i + " accepted connection from " + dataSock.getRemoteSocketAddress());
                    dataSock.setSoTimeout(60 * 1000); 

                    futures.add(pool.submit(new ReceiverWorker(dataSock, raf, cb, totalBytesActuallyReceived, fileLength)));
                } catch (IOException e) {
                    LogPanel.log("Receiver: Error accepting connection for worker " + i + ": " + e.getMessage());
                    if (dataSock != null && !dataSock.isClosed()) dataSock.close();
                    if (cb != null) cb.onError(e);
                    // If a worker fails to start, the transfer will likely be incomplete.
                    // Consider a more robust error handling strategy here, e.g., failing the entire transfer.
                }
            }
            LogPanel.log("Receiver: All connection accept loops finished. Number of futures submitted: " + futures.size());
            pool.shutdown();
            LogPanel.log("Receiver: Pool shutdown initiated. Waiting for termination...");
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) { 
                LogPanel.log("Receiver: Pool termination timeout.");
                pool.shutdownNow();
                if (cb != null) cb.onError(new IOException("Receiver tasks timed out."));
                return false; 
            }
            LogPanel.log("Receiver: Pool terminated.");

            boolean allTasksOk = true;
            for (Future<?> future : futures) {
                try {
                    future.get(); 
                } catch (Exception e) {
                    LogPanel.log("A receiver task failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
                    allTasksOk = false;
                    // cb.onError should have been called within the task itself
                }
            }

            if (allTasksOk) {
                long finalFileSize = out.length();
                LogPanel.log("Receiver: All tasks completed. Expected size: " + fileLength + ", Actual disk size: " + finalFileSize + ", Total bytes reported by workers: " + totalBytesActuallyReceived.get());
                if (finalFileSize == fileLength) {
                    // Additional check: ensure total bytes processed by workers also matches
                    if (totalBytesActuallyReceived.get() == fileLength) {
                        LogPanel.log("File received successfully: " + outputFile + ", Final Size: " + finalFileSize);
                        if (cb != null) cb.onComplete();
                        return true;
                    } else {
                        LogPanel.log("File size on disk matches expected, but total bytes processed by workers (" + totalBytesActuallyReceived.get() + ") does not match fileLength (" + fileLength + ").");
                        if (cb != null) cb.onError(new IOException("Internal inconsistency: Worker byte count mismatch. Expected " + fileLength + ", workers processed " + totalBytesActuallyReceived.get()));
                        return false;
                    }
                } else {
                    LogPanel.log("File size mismatch. Expected: " + fileLength + ", Actual disk size: " + finalFileSize + ". Total bytes reported by workers: " + totalBytesActuallyReceived.get());
                    if (cb != null) cb.onError(new IOException("File size mismatch after transfer. Expected " + fileLength + ", got " + finalFileSize));
                    return false;
                }
            } else {
                LogPanel.log("One or more receiver tasks failed. File transfer incomplete or corrupted.");
                return false;
            }

        } finally {
            for (Socket s : dataSockets) {
                if (s != null && !s.isClosed()) {
                    try {
                        s.close();
                    } catch (IOException e) {
                        LogPanel.log("Receiver: Error closing a data socket: " + e.getMessage());
                    }
                }
            }
            LogPanel.log("Receiver: All accepted data sockets closed.");
            if (!pool.isTerminated()) { // Ensure pool is fully stopped
                LogPanel.log("Receiver: Forcing pool shutdown in final finally block.");
                pool.shutdownNow();
            }
        }
    }

    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf; 
        private final TransferCallback callback;
        private final AtomicLong totalBytesActuallyReceivedOverall; 
        private final long expectedTotalFileLength;


        public ReceiverWorker(Socket dataSocket, RandomAccessFile raf, TransferCallback callback, AtomicLong totalReceived, long expectedTotalFileLength) {
            this.dataSocket = dataSocket;
            this.raf = raf;
            this.callback = callback;
            this.totalBytesActuallyReceivedOverall = totalReceived;
            this.expectedTotalFileLength = expectedTotalFileLength;
        }

        @Override
        public void run() {
            String workerName = Thread.currentThread().getName();
            LogPanel.log("ReceiverWorker ("+ workerName +"): Started.");
            try (InputStream socketInputStream = dataSocket.getInputStream();
                 ReadableByteChannel rbc = Channels.newChannel(socketInputStream)) {

                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES);
                byte[] dataBuffer = new byte[64 * 1024]; 

                while (true) { 
                    LogPanel.log("ReceiverWorker ("+ workerName +"): Top of chunk read loop. Waiting for header.");
                    headerBuffer.clear();
                    int headerBytesRead = 0;
                    try {
                        while (headerBuffer.hasRemaining()) {
                            int bytesReadThisCall = rbc.read(headerBuffer);
                            if (bytesReadThisCall == -1) {
                                if (headerBytesRead == 0) {
                                    LogPanel.log("ReceiverWorker ("+ workerName +"): Clean EOF detected on header read (0 bytes). Worker finishing as sender closed connection.");
                                    return; 
                                }
                                throw new EOFException("Socket closed prematurely while reading chunk header. Read " + headerBytesRead + " header bytes.");
                            }
                            headerBytesRead += bytesReadThisCall;
                        }
                    } catch (EOFException e) {
                        if (headerBytesRead == 0) { 
                             LogPanel.log("ReceiverWorker ("+ workerName +"): EOF on header read (no bytes read), worker finishing. Message: " + e.getMessage());
                             return;
                        }
                        LogPanel.log("ReceiverWorker ("+ workerName +"): EOFException after reading " + headerBytesRead + " header bytes: " + e.getMessage());
                        throw e; 
                    }

                    headerBuffer.flip();
                    long offset = headerBuffer.getLong();
                    long length = headerBuffer.getLong();

                    LogPanel.log(String.format("ReceiverWorker (%s): Got header: offset=%d, length=%d. Expected total file length: %d", workerName, offset, length, expectedTotalFileLength));

                    if (length < 0) {
                        throw new IOException(String.format("ReceiverWorker (%s): Received invalid chunk: negative length %d", workerName, length));
                    }

                    // --- Start of new validation logic ---
                    if (expectedTotalFileLength == 0) { 
                        if (offset == 0 && length == 0) {
                            LogPanel.log(String.format("ReceiverWorker (%s): Received and validated (0,0) chunk for zero-byte file. Worker finishing.", workerName));
                            return; 
                        } else {
                            throw new IOException(String.format(
                                "ReceiverWorker (%s): Invalid chunk (offset=%d, length=%d) for expected zero-byte file.",
                                workerName, offset, length
                            ));
                        }
                    }

                    // For non-empty files:
                    if (offset < 0) {
                         throw new IOException(String.format(
                            "ReceiverWorker (%s): Invalid chunk offset %d. Cannot be negative.",
                            workerName, offset
                        ));
                    }
                    // A chunk cannot start at or after the end of the file, unless it's a zero-length chunk (which is handled if length == 0 below)
                    if (offset >= expectedTotalFileLength && length > 0) {
                         throw new IOException(String.format(
                            "ReceiverWorker (%s): Invalid chunk offset %d for non-empty chunk. Must be less than expected file length %d.",
                            workerName, offset, expectedTotalFileLength
                        ));
                    }
                    // The end of the chunk cannot exceed the end of the file.
                    if (offset + length > expectedTotalFileLength) {
                        throw new IOException(String.format(
                            "ReceiverWorker (%s): Chunk (offset=%d, length=%d) would exceed expected file length %d.",
                            workerName, offset, length, expectedTotalFileLength
                        ));
                    }
                    // --- End of new validation logic ---

                    if (length == 0) { 
                        // This implies expectedTotalFileLength > 0 (covered above)
                        // Sender should not send (offset, 0) chunks for non-empty files if offset < expectedTotalFileLength.
                        // If it does, it's a no-op for data transfer here.
                        LogPanel.log(String.format("ReceiverWorker (%s): Received zero-length chunk (offset=%d) for non-empty file. Skipping data read.", workerName, offset));
                        continue; 
                    }

                    long bytesReadForThisChunk = 0;
                    while (bytesReadForThisChunk < length) {
                        int toRead = (int) Math.min(dataBuffer.length, length - bytesReadForThisChunk);
                        int n = socketInputStream.read(dataBuffer, 0, toRead);
                        if (n == -1) {
                            throw new EOFException(String.format(
                                    "ReceiverWorker (%s): Socket closed prematurely. Expected %d bytes for chunk (offset %d, length %d), but received only %d before EOF.",
                                    workerName, length, offset, bytesReadForThisChunk
                            ));
                        }
                        synchronized (raf) {
                            raf.seek(offset + bytesReadForThisChunk);
                            raf.write(dataBuffer, 0, n);
                        }
                        bytesReadForThisChunk += n;
                        totalBytesActuallyReceivedOverall.addAndGet(n);
                        if (callback != null) {
                            callback.onProgress(n);
                        }
                    }
                    LogPanel.log(String.format("ReceiverWorker (%s): Finished receiving chunk: offset=%d, length=%d. Total for this chunk: %d", workerName, offset, length, bytesReadForThisChunk));
                } 
            } catch (SocketTimeoutException e) {
                LogPanel.log("Error in ReceiverWorker ("+ workerName +"): Socket timeout - " + e.getMessage());
                if (callback != null) callback.onError(e);
                throw new RuntimeException("Socket timeout in ReceiverWorker for " + workerName, e);
            } catch (IOException e) {
                LogPanel.log("Error in ReceiverWorker ("+ workerName +"): " + e.getClass().getSimpleName() + " - " + e.getMessage());
                if (callback != null) {
                    callback.onError(e);
                }
                throw new RuntimeException("IOException in ReceiverWorker for " + workerName, e); 
            } finally {
                LogPanel.log("ReceiverWorker ("+ workerName +") finishing run method.");
            }
        }
    }
}
