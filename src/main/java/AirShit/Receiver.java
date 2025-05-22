package AirShit;

import AirShit.ui.LogPanel;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
                         long fileLength, // Expected total file length from handshake
                         int threadCount,  // Negotiated thread count
                         TransferCallback cb) throws InterruptedException {

        File out = new File(outputFile);
        if (out.getParentFile() != null && !out.getParentFile().exists()) {
            if (!out.getParentFile().mkdirs()) {
                LogPanel.log("Receiver: Failed to create parent directories for " + outputFile);
                if (cb != null) cb.onError(new IOException("Failed to create parent directories for " + outputFile));
                return false;
            }
        }
        if (out.exists()) {
            if (!out.delete()) {
                LogPanel.log("Receiver: Failed to delete existing file " + outputFile);
                 if (cb != null) cb.onError(new IOException("Failed to delete existing file " + outputFile));
                return false;
            }
        }
        
        // Handle zero-byte file creation explicitly
        if (fileLength == 0) {
            LogPanel.log("Receiver: Expecting a zero-byte file: " + outputFile);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                // Empty file created, no further action needed from workers for data.
            } catch (IOException e) {
                LogPanel.log("Receiver: Failed to create zero-byte file " + outputFile + ": " + e.getMessage());
                if (cb != null) cb.onError(e);
                return false;
            }
        }

        LogPanel.log("Receiver starting. Expecting file: " + outputFile + ", Size: " + fileLength + ", Connections: " + threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        List<Socket> dataSockets = new ArrayList<>(); // Keep track of sockets to close them

        AtomicLong totalBytesActuallyReceivedOverall = new AtomicLong(0);

        try (RandomAccessFile raf = (fileLength > 0) ? new RandomAccessFile(out, "rw") : null) {
            // For zero-byte files, raf is null, workers will handle the (0,0) chunk and return.
            // For non-zero files, RandomAccessFile is used.
            // Optional: Pre-allocate space. Can be slow. Not doing it by default.
            // if (raf != null && fileLength > 0) {
            //    raf.setLength(fileLength); // This can be problematic if transfer fails midway
            // }

            for (int i = 0; i < threadCount; i++) {
                Socket dataSock = null;
                try {
                    LogPanel.log("Receiver: Worker " + i + " waiting to accept connection on " + serverSocket.getLocalSocketAddress() + "...");
                    dataSock = serverSocket.accept(); // Accept a connection for this worker
                    dataSockets.add(dataSock); // Add to list for later cleanup
                    LogPanel.log("Receiver: Worker " + i + " accepted connection from " + dataSock.getRemoteSocketAddress());
                    // dataSock.setSoTimeout(5 * 60 * 1000); // Original 5-minute timeout
                    dataSock.setSoTimeout(30 * 60 * 1000); // Increase to 30 minutes for socket operations

                    futures.add(pool.submit(new ReceiverWorker(dataSock, raf, cb, totalBytesActuallyReceivedOverall, fileLength)));
                } catch (IOException e) {
                    LogPanel.log("Receiver: Error accepting connection for worker " + i + ": " + e.getMessage());
                    if (dataSock != null && !dataSock.isClosed()) {
                        try { dataSock.close(); } catch (IOException ex) { LogPanel.log("Error closing dataSock on accept error: " + ex.getMessage()); }
                    }
                    if (cb != null) cb.onError(e);
                    // If a worker fails to even accept a connection, the transfer is likely doomed.
                    // Consider a strategy to signal all other workers to stop or fail the transfer immediately.
                    // For now, we let other workers proceed, but the transfer will likely fail overall.
                }
            }
            LogPanel.log("Receiver: All connection accept loops finished. Number of futures submitted: " + futures.size());

            if (futures.isEmpty() && threadCount > 0) {
                LogPanel.log("Receiver: No worker tasks were submitted, though threads were expected. Aborting.");
                if (cb != null) cb.onError(new IOException("No receiver worker tasks started."));
                pool.shutdownNow();
                return false;
            }


            pool.shutdown(); // Signal that no new tasks will be submitted
            LogPanel.log("Receiver: Pool shutdown initiated. Waiting for termination...");
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) { // Wait for tasks to complete
                LogPanel.log("Receiver: Pool termination timeout. Forcing shutdown.");
                pool.shutdownNow(); // Forcefully stop tasks
                if (cb != null) cb.onError(new IOException("Receiver tasks timed out."));
                return false; // Transfer failed due to timeout
            }
            LogPanel.log("Receiver: Pool terminated.");

            boolean allTasksOk = true;
            for (Future<?> future : futures) {
                try {
                    future.get(); // Check for exceptions from worker tasks
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // Preserve interrupt status
                    LogPanel.log("Receiver main thread interrupted while waiting for a worker: " + e.getMessage());
                    allTasksOk = false;
                    if (cb != null) cb.onError(e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    LogPanel.log("A receiver task failed with ExecutionException. Cause: " + (cause != null ? cause.getClass().getSimpleName() + " - " + cause.getMessage() : "Unknown cause"));
                    // cause.printStackTrace(); // For detailed debugging
                    allTasksOk = false;
                    // The worker's onError should have been called already if 'cause' is an Exception
                    // If cb.onError wasn't called by worker, call it here.
                    if (cb != null) {
                        if (cause instanceof Exception) {
                            // cb.onError((Exception) cause); // Worker should have called this.
                        } else {
                            // cb.onError(new Exception("Receiver worker failed with non-Exception cause", cause));
                        }
                    }
                }
            }

            if (allTasksOk) {
                // For zero-byte files, out.length() should be 0.
                // totalBytesActuallyReceivedOverall should also be 0.
                if (fileLength == 0) {
                    if (out.exists() && out.length() == 0 && totalBytesActuallyReceivedOverall.get() == 0) {
                        LogPanel.log("Zero-byte file received successfully: " + outputFile);
                        if (cb != null) cb.onComplete();
                        return true;
                    } else {
                        LogPanel.log("Zero-byte file discrepancy. Expected size: 0, Actual disk size: " + (out.exists() ? out.length() : "N/A") + ", Reported by workers: " + totalBytesActuallyReceivedOverall.get());
                        if (cb != null) cb.onError(new IOException("Zero-byte file reception failed validation."));
                        return false;
                    }
                }

                // For non-zero byte files
                long finalFileSizeOnDisk = out.length();
                LogPanel.log("Receiver: All tasks completed. Expected size: " + fileLength + ", Actual disk size: " + finalFileSizeOnDisk + ", Total bytes reported by workers: " + totalBytesActuallyReceivedOverall.get());
                if (finalFileSizeOnDisk == fileLength) {
                    // Additional check: ensure total bytes processed by workers also matches
                    if (totalBytesActuallyReceivedOverall.get() == fileLength) {
                        LogPanel.log("File received successfully: " + outputFile + ", Final Size: " + finalFileSizeOnDisk);
                        if (cb != null) cb.onComplete();
                        return true;
                    } else {
                        LogPanel.log("File size on disk matches expected, but total bytes processed by workers (" + totalBytesActuallyReceivedOverall.get() + ") does not match fileLength (" + fileLength + ").");
                        if (cb != null) cb.onError(new IOException("Internal inconsistency: Worker byte count mismatch. Expected " + fileLength + ", workers processed " + totalBytesActuallyReceivedOverall.get()));
                        return false;
                    }
                } else {
                    LogPanel.log("File size mismatch. Expected: " + fileLength + ", Actual disk size: " + finalFileSizeOnDisk + ". Total bytes reported by workers: " + totalBytesActuallyReceivedOverall.get());
                    if (cb != null) cb.onError(new IOException("File size mismatch after transfer. Expected " + fileLength + ", got " + finalFileSizeOnDisk));
                    return false;
                }
            } else {
                LogPanel.log("One or more receiver tasks failed. File transfer incomplete or corrupted.");
                // cb.onError should have been called by the failing task or by the ExecutionException handler.
                return false;
            }

        } catch (IOException e) { // Catches IOException from new RandomAccessFile
            LogPanel.log("Receiver: IOException during RandomAccessFile setup or outer scope: " + e.getMessage());
            if (cb != null) cb.onError(e);
            return false;
        } finally {
            for (Socket s : dataSockets) { // Close all sockets that were accepted
                if (s != null && !s.isClosed()) {
                    try {
                        s.close();
                    } catch (IOException e) {
                        LogPanel.log("Receiver: Error closing a data socket in finally: " + e.getMessage());
                    }
                }
            }
            LogPanel.log("Receiver: All accepted data sockets attempted to close.");
            if (!pool.isTerminated()) { // Ensure pool is fully stopped
                LogPanel.log("Receiver: Forcing pool shutdown in final finally block.");
                pool.shutdownNow();
            }
        }
    }

    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf; // Can be null if expectedTotalFileLength is 0
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

                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES); // offset + length
                byte[] dataBuffer = new byte[64 * 1024]; // Buffer for reading data

                while (true) { // Loop to read multiple chunks if sender sends them on this connection
                    LogPanel.log("ReceiverWorker ("+ workerName +"): Top of chunk read loop. Waiting for header.");
                    headerBuffer.clear();
                    int headerBytesReadCount = 0;
                    try {
                        while (headerBuffer.hasRemaining()) {
                            int bytesReadThisCall = rbc.read(headerBuffer);
                            if (bytesReadThisCall == -1) { // EOF
                                if (headerBytesReadCount == 0) { // Clean EOF before any header byte for this chunk
                                    LogPanel.log("ReceiverWorker ("+ workerName +"): Clean EOF detected on header read (0 bytes). Worker finishing as sender likely closed connection after sending all its chunks for this worker.");
                                    return; // Normal termination for this worker
                                }
                                // EOF in the middle of a header
                                throw new EOFException("Socket closed prematurely while reading chunk header. Expected " + headerBuffer.capacity() + " header bytes, read " + headerBytesReadCount);
                            }
                            headerBytesReadCount += bytesReadThisCall;
                        }
                    } catch (EOFException e) { // Catch EOF specifically from the header read loop
                        if (headerBytesReadCount == 0 && !headerBuffer.hasRemaining()) { // This case should be caught by bytesReadThisCall == -1 above
                             LogPanel.log("ReceiverWorker ("+ workerName +"): EOF on header read (no bytes read), worker finishing. Message: " + e.getMessage());
                             return; // Normal if sender closes after sending all its data.
                        }
                        // If EOF occurs after some header bytes, it's an error.
                        LogPanel.log("ReceiverWorker ("+ workerName +"): EOFException after reading " + headerBytesReadCount + " header bytes: " + e.getMessage());
                        throw e; // Propagate as it's an incomplete header
                    }

                    headerBuffer.flip();
                    long offset = headerBuffer.getLong();
                    long length = headerBuffer.getLong();

                    LogPanel.log(String.format("ReceiverWorker (%s): Got header: offset=%d, length=%d. Expected total file length: %d", workerName, offset, length, expectedTotalFileLength));

                    // --- Start of validation logic ---
                    if (length < 0) {
                        throw new IOException(String.format("ReceiverWorker (%s): Received invalid chunk: negative length %d for offset %d", workerName, length, offset));
                    }

                    if (expectedTotalFileLength == 0) { // Special case for zero-byte files
                        if (offset == 0 && length == 0) {
                            LogPanel.log(String.format("ReceiverWorker (%s): Received and validated (0,0) chunk for zero-byte file. Worker finishing.", workerName));
                            // No data to read, no raf to use. This worker's job is done.
                            return;
                        } else {
                            throw new IOException(String.format(
                                "ReceiverWorker (%s): Invalid chunk (offset=%d, length=%d) for expected zero-byte file. Expected (0,0).",
                                workerName, offset, length
                            ));
                        }
                    }

                    // Validations for non-empty files (expectedTotalFileLength > 0)
                    if (offset < 0) {
                         throw new IOException(String.format(
                            "ReceiverWorker (%s): Invalid chunk offset %d. Cannot be negative.",
                            workerName, offset
                        ));
                    }
                    // A chunk cannot start at or after the end of the file if it has data.
                    if (offset >= expectedTotalFileLength && length > 0) {
                         throw new IOException(String.format(
                            "ReceiverWorker (%s): Invalid chunk offset %d for non-empty chunk (length %d). Offset must be less than expected file length %d.",
                            workerName, offset, length, expectedTotalFileLength
                        ));
                    }
                    // The end of the chunk cannot exceed the end of the file.
                    if (offset + length > expectedTotalFileLength) {
                        // Allow offset + length == expectedTotalFileLength
                        throw new IOException(String.format(
                            "ReceiverWorker (%s): Chunk (offset=%d, length=%d) would exceed expected file length %d. Sum is %d.",
                            workerName, offset, length, expectedTotalFileLength, (offset + length)
                        ));
                    }
                    // --- End of validation logic ---

                    if (length == 0) {
                        // If expectedTotalFileLength > 0, an (offset, 0) chunk is unusual but not strictly an error by this validation.
                        // It means no data to read for this chunk.
                        LogPanel.log(String.format("ReceiverWorker (%s): Received zero-length chunk (offset=%d) for non-empty file. Skipping data read for this chunk.", workerName, offset));
                        continue; // Go to read next header
                    }

                    // If we reach here, length > 0 and expectedTotalFileLength > 0. So raf should not be null.
                    if (raf == null) {
                        // This should not happen if expectedTotalFileLength > 0.
                        throw new IOException(String.format("ReceiverWorker (%s): RandomAccessFile is null but received chunk with data (offset=%d, length=%d) for expected file length %d.",
                                workerName, offset, length, expectedTotalFileLength));
                    }

                    long bytesReadForThisChunk = 0;
                    while (bytesReadForThisChunk < length) {
                        int toRead = (int) Math.min(dataBuffer.length, length - bytesReadForThisChunk);
                        int n = socketInputStream.read(dataBuffer, 0, toRead); // Read from socket
                        if (n == -1) { // EOF while reading chunk data
                            throw new EOFException(String.format(
                                    "ReceiverWorker (%s): Socket closed prematurely while reading data for chunk. Expected %d bytes for chunk (offset %d, length %d), but received only %d before EOF.",
                                    workerName, length, offset, bytesReadForThisChunk
                            ));
                        }
                        synchronized (raf) { // Synchronize access to the shared RandomAccessFile
                            raf.seek(offset + bytesReadForThisChunk);
                            raf.write(dataBuffer, 0, n);
                        }
                        bytesReadForThisChunk += n;
                        totalBytesActuallyReceivedOverall.addAndGet(n); // Update global counter
                        if (callback != null) {
                            callback.onProgress(n); // Report progress
                        }
                    }
                    LogPanel.log(String.format("ReceiverWorker (%s): Finished receiving and writing chunk: offset=%d, length=%d. Total for this chunk: %d", workerName, offset, length, bytesReadForThisChunk));
                } // End of while(true) loop for reading chunks

            } catch (SocketTimeoutException e) {
                LogPanel.log("Error in ReceiverWorker ("+ workerName +"): Socket timeout - " + e.getMessage());
                if (callback != null) callback.onError(e);
                throw new RuntimeException("Socket timeout in ReceiverWorker for " + workerName, e); // Propagate to be caught by Future.get()
            } catch (IOException e) { // Catches validation IOExceptions, EOFExceptions from data read, etc.
                LogPanel.log("Error in ReceiverWorker ("+ workerName +"): " + e.getClass().getSimpleName() + " - " + e.getMessage());
                // e.printStackTrace(); // For detailed debugging
                if (callback != null) {
                    callback.onError(e);
                }
                // Re-throw to be caught by Future.get() in Receiver.start()
                // This will mark the task as failed.
                throw new RuntimeException("IOException in ReceiverWorker for " + workerName, e);
            } finally {
                LogPanel.log("ReceiverWorker ("+ workerName +") finishing run method.");
                // InputStream and ReadableByteChannel are closed by try-with-resources.
                // The dataSocket itself is closed by the main Receiver.start() method's finally block.
            }
        }
    }
}
