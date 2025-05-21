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
        
        // Create an empty file if fileLength is 0, otherwise RandomAccessFile will handle creation
        if (fileLength == 0) {
            LogPanel.log("Receiver: Expecting a zero-byte file: " + outputFile);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                // Empty file created
            }
            // The sender will send one "zero-length" chunk. We need to accept one connection for it.
        }

        LogPanel.log("Receiver starting. Expecting file: " + outputFile + ", Size: " + fileLength + ", Connections: " + threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        List<Socket> dataSockets = new ArrayList<>();

        AtomicLong totalBytesActuallyReceived = new AtomicLong(0);

        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            // if (fileLength > 0) { // Pre-allocate space only for non-empty files
                 // raf.setLength(fileLength); // Optional: pre-allocate, can be slow for large files on some OS
            // }

            for (int i = 0; i < threadCount; i++) {
                Socket dataSock = null;
                try {
                    LogPanel.log("Receiver: Worker " + i + " waiting to accept connection on " + serverSocket.getLocalSocketAddress() + "..."); // UNCOMMENT/ADD
                    dataSock = serverSocket.accept(); // This is where it might block
                    dataSockets.add(dataSock);
                    LogPanel.log("Receiver: Worker " + i + " accepted connection from " + dataSock.getRemoteSocketAddress()); // UNCOMMENT/ADD
                    dataSock.setSoTimeout(60 * 1000); // 60 seconds timeout for inactivity on a chunk read

                    futures.add(pool.submit(new ReceiverWorker(dataSock, raf, cb, totalBytesActuallyReceived, fileLength)));
                } catch (IOException e) {
                    LogPanel.log("Receiver: Error accepting connection for worker " + i + ": " + e.getMessage()); // Already there
                    if (dataSock != null && !dataSock.isClosed()) dataSock.close();
                    // If a connection accept fails, we might not receive all data.
                    // Propagate error or decide how to handle partial reception.
                    // For now, we'll let other workers try, but this is a critical failure.
                    if (cb != null) cb.onError(e);
                    // Consider shutting down the pool and failing fast if not all workers can start
                    // For this example, we continue, which might lead to incomplete file.
                }
            }
            LogPanel.log("Receiver: All connection accept loops finished. Number of futures: " + futures.size()); // ADD
            pool.shutdown();
            LogPanel.log("Receiver: Pool shutdown initiated. Waiting for termination..."); // ADD
            if (!pool.awaitTermination(24, TimeUnit.HOURS)) { // Long timeout
                LogPanel.log("Receiver: Pool termination timeout.");
                pool.shutdownNow();
                if (cb != null) cb.onError(new IOException("Receiver tasks timed out."));
                return false; // Indicate failure
            }
            LogPanel.log("Receiver: Pool terminated."); // Already there (as "All receiver worker tasks have been submitted and pool has shut down.")

            // Validate all futures completed without exceptions
            boolean allTasksOk = true;
            for (Future<?> future : futures) {
                try {
                    future.get(); // This will throw an exception if the task threw one
                } catch (Exception e) {
                    LogPanel.log("A receiver task failed: " + e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    allTasksOk = false;
                    // cb.onError was likely already called within the task
                }
            }

            if (allTasksOk) {
                // Final check on file size
                if (out.length() == fileLength) {
                    LogPanel.log("File received successfully: " + outputFile + ", Final Size: " + out.length());
                    if (cb != null) cb.onComplete();
                    return true;
                } else {
                    LogPanel.log("File size mismatch. Expected: " + fileLength + ", Actual: " + out.length() + " (Total reported by workers: " + totalBytesActuallyReceived.get() + ")");
                    if (cb != null) cb.onError(new IOException("File size mismatch after transfer. Expected " + fileLength + ", got " + out.length()));
                    return false;
                }
            } else {
                LogPanel.log("One or more receiver tasks failed. File transfer incomplete or corrupted.");
                // cb.onError would have been called by the failing task(s)
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
        }
    }

    private static class ReceiverWorker implements Runnable {
        private final Socket dataSocket;
        private final RandomAccessFile raf; // Shared
        private final TransferCallback callback;
        private final AtomicLong totalBytesActuallyReceivedOverall; // For final verification
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
            LogPanel.log("ReceiverWorker ("+Thread.currentThread().getName()+"): Started."); // ADD THIS
            try (InputStream socketInputStream = dataSocket.getInputStream();
                 ReadableByteChannel rbc = Channels.newChannel(socketInputStream)) {

                ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES);
                byte[] dataBuffer = new byte[64 * 1024]; 

                while (true) { // This loop reads multiple chunks from one connection
                    LogPanel.log("ReceiverWorker ("+Thread.currentThread().getName()+"): Top of chunk read loop. Waiting for header."); // ADD
                    headerBuffer.clear();
                    int headerBytesRead = 0;
                    try {
                        while (headerBuffer.hasRemaining()) {
                            int bytesReadThisCall = rbc.read(headerBuffer);
                            if (bytesReadThisCall == -1) {
                                if (headerBytesRead == 0) {
                                    LogPanel.log("ReceiverWorker ("+Thread.currentThread().getName()+"): Clean EOF detected on header read (0 bytes). Worker finishing as sender closed connection.");
                                    return; // Correct: End of chunks for this worker
                                }
                                throw new EOFException("Socket closed prematurely while reading chunk header. Read " + headerBytesRead + " header bytes.");
                            }
                            headerBytesRead += bytesReadThisCall;
                        }
                    } catch (EOFException e) {
                        if (headerBytesRead == 0) { // Double check if this path is reachable given the above
                             LogPanel.log("ReceiverWorker ("+Thread.currentThread().getName()+"): EOF on header read (no bytes read), worker finishing. Message: " + e.getMessage());
                             return;
                        }
                        // If some header bytes were read before EOF, it's an error.
                        LogPanel.log("ReceiverWorker ("+Thread.currentThread().getName()+"): EOFException after reading " + headerBytesRead + " header bytes: " + e.getMessage());
                        throw e; // Propagate as it's an incomplete header
                    }


                    headerBuffer.flip();
                    long offset = headerBuffer.getLong();
                    long length = headerBuffer.getLong();

                    LogPanel.log(String.format("ReceiverWorker (%s): Got header: offset=%d, length=%d", Thread.currentThread().getName(), offset, length)); // UNCOMMENT/MODIFY

                    if (length < 0) {
                        throw new IOException("Received invalid chunk length: " + length);
                    }
                    
                    if (length == 0) { // Handle zero-byte file signal or empty chunk
                        // LogPanel.log(String.format("ReceiverWorker (%s): Received zero-length chunk for offset=%d. Handled.", Thread.currentThread().getName(), offset));
                        // If this is the *only* chunk (for a zero-byte file), this worker might be the one to receive it.
                        // The main `start` method already creates an empty file if fileLength is 0.
                        // If this is the only chunk and it's for a zero-byte file, the worker can simply return.
                        // The sender sends one (0,0) chunk for empty files.
                        if (expectedTotalFileLength == 0 && offset == 0) {
                            return; // This worker handled the zero-byte file signal
                        }
                        continue; // If it's just an empty part of a larger file (should not happen with current sender)
                    }


                    long bytesReadForThisChunk = 0;
                    while (bytesReadForThisChunk < length) {
                        int toRead = (int) Math.min(dataBuffer.length, length - bytesReadForThisChunk);
                        int n = socketInputStream.read(dataBuffer, 0, toRead);
                        if (n == -1) {
                            throw new EOFException(String.format(
                                    "Socket closed prematurely. Expected %d bytes for chunk (offset %d), but received only %d before EOF.",
                                    length, offset, bytesReadForThisChunk
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
                    // LogPanel.log(String.format("ReceiverWorker (%s): Finished receiving chunk: offset=%d, length=%d", Thread.currentThread().getName(), offset, length));
                } // End of while(true) for reading chunks
            } catch (SocketTimeoutException e) {
                LogPanel.log("Error in ReceiverWorker ("+Thread.currentThread().getName()+"): Socket timeout - " + e.getMessage());
                if (callback != null) callback.onError(e);
                throw new RuntimeException("Socket timeout in ReceiverWorker", e);
            } catch (IOException e) {
                LogPanel.log("Error in ReceiverWorker ("+Thread.currentThread().getName()+"): " + e.getMessage());
                // e.printStackTrace(); // For debugging
                if (callback != null) {
                    callback.onError(e);
                }
                throw new RuntimeException("IOException in ReceiverWorker", e); // Propagate to mark Future as failed
            } finally {
                LogPanel.log("ReceiverWorker ("+Thread.currentThread().getName()+") finishing run method."); // UNCOMMENT/ADD
            }
        }
    }
}
