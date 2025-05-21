package AirShit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer; // Import ByteBuffer
import java.nio.channels.Channels; // Import Channels
import java.nio.channels.ReadableByteChannel; // Import ReadableByteChannel
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
// It seems you are not using FileReceiver's TransferCallback directly in Receiver,
// but a generic TransferCallback. Ensure it's the correct one.

public class Receiver {
    private final ServerSocket serverSocket; // This is the ServerSocket passed from FileReceiver

    public Receiver(ServerSocket ss) {
        this.serverSocket = ss;
    }

    /**
     * Starts receiving file chunks.
     *
     * @param outputFile  The path to save the received file.
     * @param fileLength  The total expected length of the file.
     * @param threadCount The number of concurrent threads/connections expected from the sender.
     * @param cb          Callback for progress and errors.
     * @return true if all chunks are received successfully, false otherwise.
     * @throws IOException          If an I/O error occurs.
     * @throws InterruptedException If the thread is interrupted while waiting.
     */
    public boolean start(String outputFile,
                         long fileLength,
                         int threadCount, // This is the negotiated thread count
                         TransferCallback cb) throws IOException, InterruptedException {
        File out = new File(outputFile);
        if (out.getParentFile() != null) {
            out.getParentFile().mkdirs();
        }
        // It's good practice to delete a potentially incomplete file from a previous attempt
        if (out.exists()) {
            out.delete();
        }
        // Create the file for RandomAccessFile
        // RandomAccessFile will create it if it doesn't exist, but creating it explicitly is fine.
        // out.createNewFile(); // Not strictly necessary if using "rw" in RAF

        // Calculate the total number of chunks the sender will send.
        // This logic must exactly match SendFile.java's chunk calculation.
        long baseChunkSizeSender = Math.min(fileLength, 5L * 1024 * 1024 * 1024);
        long workerCountSender = (long) Math.ceil((double) fileLength / (double) baseChunkSizeSender);
        
        int totalChunksExpected = 0;
        long alreadyCalculatedBytes = 0;
        long chunkSizeSender = (baseChunkSizeSender + threadCount - 1) / threadCount;


        for (int i = 0; i < workerCountSender; i++) {
            long processingForBaseChunk = Math.min(baseChunkSizeSender, fileLength - alreadyCalculatedBytes);
            for (int j = 0; j < threadCount; j++) {
                long offsetInFile = (j * chunkSizeSender) + alreadyCalculatedBytes;
                if (offsetInFile >= alreadyCalculatedBytes + processingForBaseChunk) {
                    break;
                }
                long tempChunkSizeForSender;
                if (j == threadCount - 1) {
                    tempChunkSizeForSender = (alreadyCalculatedBytes + processingForBaseChunk) - offsetInFile;
                } else {
                    tempChunkSizeForSender = Math.min(chunkSizeSender, (alreadyCalculatedBytes + processingForBaseChunk) - offsetInFile);
                }
                if (tempChunkSizeForSender > 0) {
                    totalChunksExpected++;
                }
            }
            alreadyCalculatedBytes += processingForBaseChunk;
        }
        
        if (fileLength == 0 && totalChunksExpected == 0) { // Handle zero-byte files
             LogPanel.log("Receiving a zero-byte file: " + outputFile);
             try (FileOutputStream fos = new FileOutputStream(out)) {
                 // Create an empty file
             }
             if (cb != null) cb.onComplete();
             return true;
        }
        
        if (totalChunksExpected == 0 && fileLength > 0) {
            LogPanel.log("Error: Calculated 0 chunks for a non-zero file length. FileLength: " + fileLength);
            if (cb != null) cb.onError(new IOException("Calculated 0 chunks for a non-zero file."));
            return false;
        }


        LogPanel.log("Receiver expecting " + totalChunksExpected + " chunks for file: " + outputFile);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        long totalBytesReceived = 0; // To track overall progress for onComplete

        try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            // Pre-allocate file space if possible and desired (optional, can improve performance)
            // raf.setLength(fileLength); // Be cautious with this if fileLength is very large

            for (int i = 0; i < totalChunksExpected; i++) {
                Socket dataSock = null;
                try {
                    // LogPanel.log("Receiver waiting to accept connection for chunk " + (i + 1) + "/" + totalChunksExpected);
                    dataSock = serverSocket.accept(); // Accept a new connection for each chunk
                    // LogPanel.log("Receiver accepted connection for chunk " + (i + 1) + " from " + dataSock.getRemoteSocketAddress());
                    
                    // It's good to set a timeout for read operations on the data socket
                    dataSock.setSoTimeout(30 * 1000); // 30 seconds timeout for reading a chunk

                    Socket finalDataSock = dataSock; // Effectively final for lambda
                    futures.add(pool.submit(() -> {
                        try (InputStream socketInputStream = finalDataSock.getInputStream()) {
                            // Use ReadableByteChannel for more efficient header reading with ByteBuffer
                            ReadableByteChannel rbc = Channels.newChannel(socketInputStream);
                            ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Long.BYTES);

                            // Read the header (long offset, long length)
                            while (headerBuffer.hasRemaining()) {
                                int bytesRead = rbc.read(headerBuffer);
                                if (bytesRead == -1) {
                                    throw new EOFException("Socket closed prematurely while reading chunk header.");
                                }
                            }
                            headerBuffer.flip();
                            long offset = headerBuffer.getLong();
                            long length = headerBuffer.getLong(); // Corrected to long

                            // LogPanel.log(String.format("Receiver: Got header for chunk: offset=%d, length=%d", offset, length));

                            if (length < 0) {
                                throw new IOException("Received invalid chunk length: " + length);
                            }
                            if (length == 0) { // Sender should not send zero-length chunks based on its logic
                                // LogPanel.log(String.format("Receiver: Received zero-length chunk for offset=%d. Skipping write.", offset));
                                return; // Nothing to write
                            }


                            byte[] buffer = new byte[32 * 1024]; // 32KB buffer
                            long bytesReadForThisChunk = 0;
                            int n;

                            while (bytesReadForThisChunk < length &&
                                   (n = socketInputStream.read(buffer, 0, (int) Math.min(buffer.length, length - bytesReadForThisChunk))) != -1) {
                                synchronized (raf) { // Synchronize access to RandomAccessFile
                                    raf.seek(offset + bytesReadForThisChunk);
                                    raf.write(buffer, 0, n);
                                }
                                bytesReadForThisChunk += n;
                                if (cb != null) {
                                    cb.onProgress(n);
                                }
                            }

                            if (bytesReadForThisChunk < length) {
                                throw new EOFException(String.format(
                                    "Socket closed prematurely. Expected %d bytes for chunk (offset %d), but received only %d.",
                                    length, offset, bytesReadForThisChunk
                                ));
                            }
                            // LogPanel.log(String.format("Receiver: Finished receiving chunk: offset=%d, length=%d", offset, length));

                        } catch (IOException e) {
                            LogPanel.log("Error processing chunk in receiver thread: " + e.getMessage());
                            // e.printStackTrace(); // For debugging
                            if (cb != null) {
                                cb.onError(e);
                            }
                            // Propagate the exception so Future.get() can catch it
                            throw new RuntimeException("Failed to process chunk", e);
                        } finally {
                            try {
                                if (finalDataSock != null && !finalDataSock.isClosed()) {
                                    finalDataSock.close();
                                }
                            } catch (IOException ex) {
                                // LogPanel.log("Error closing data socket in receiver thread: " + ex.getMessage());
                            }
                        }
                    }));
                } catch (IOException e) { // Catch accept() errors or initial socket setup errors
                    LogPanel.log("Receiver error accepting connection or setting up socket for chunk: " + e.getMessage());
                    if (cb != null) cb.onError(e);
                    // If accept fails, we might not be able to receive all chunks.
                    // Close any opened socket and rethrow or handle to stop further processing.
                    if (dataSock != null && !dataSock.isClosed()) dataSock.close();
                    pool.shutdownNow(); // Attempt to stop all active tasks
                    throw e; // Rethrow to signal failure of the start method
                }
            }
        } // try-with-resources for RandomAccessFile will close raf

        pool.shutdown();
        boolean terminatedCleanly = pool.awaitTermination(1, TimeUnit.HOURS); // Wait for all tasks to complete

        if (!terminatedCleanly) {
            LogPanel.log("Receiver pool did not terminate cleanly. Some tasks might not have completed.");
            pool.shutdownNow();
            if (cb != null) cb.onError(new IOException("Receiver tasks timed out."));
            return false;
        }
        
        // Validate all futures completed without exceptions
        boolean allOk = true;
        for (Future<?> future : futures) {
            try {
                future.get(); // This will throw an exception if the task threw one
            } catch (Exception e) {
                // LogPanel.log("A receiver task failed: " + e.getMessage());
                // e.printStackTrace(); // For debugging
                // cb.onError was likely already called within the task
                allOk = false;
                // No need to call cb.onError(e) again if it was called inside the lambda
            }
        }

        if (allOk) {
            // Verify file size after all chunks are processed
            if (out.length() == fileLength) {
                LogPanel.log("File received successfully: " + outputFile + ", Size: " + out.length());
                if (cb != null) cb.onComplete();
            } else {
                LogPanel.log("File size mismatch. Expected: " + fileLength + ", Actual: " + out.length());
                if (cb != null) cb.onError(new IOException("File size mismatch after transfer."));
                allOk = false;
            }
        } else {
            LogPanel.log("One or more receiver tasks failed. File transfer incomplete or corrupted.");
            // cb.onError would have been called by the failing task(s)
        }
        return allOk;
    }
}
