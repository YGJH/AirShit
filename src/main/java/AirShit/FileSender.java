package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import AirShit.ui.*;
import AirShit.ui.LogPanel;

public class FileSender {
    private String host;
    private int port;
    // private int Compress_file_size = 5 * 1024; // Unused
    // private int Single_thread = 1 * 1024 * 1024; // Unused
    // private StringBuilder sb; // Re-initialize per call
    // private long total_files_size = 0; // Re-initialize per call
    // private File[] pSendFiles; // Unused
    // private int filesCount; // Unused
    // private int threadCount = 0; // Determined by negotiation
    private SendFile senderInstance; // Renamed from sender
    private final int ITHREADS = Runtime.getRuntime().availableProcessors();
    private final String THREADS_STR = Integer.toString(ITHREADS);

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 10;
    private static final int MAX_HANDSHAKE_RETRIES = 3;

    public FileSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void println(String str) {
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    public void sendFiles(File file, String senderUserName, TransferCallback callback) throws InterruptedException { // Removed IOException from signature, handle internally
        if (file == null) {
            LogPanel.log("FileSender: File to send is null. Aborting.");
            if (callback != null) callback.onError(new IllegalArgumentException("File to send cannot be null."));
            return;
        }

        long currentTotalFileSize = 0;
        StringBuilder handshakeBuilder = new StringBuilder();
        String archFile = null; // Path to the file that will actually be sent (original or compressed)
        boolean isCompress = false;

        handshakeBuilder.append(senderUserName).append("@");
        try {
            if (file.isDirectory()) {
                isCompress = true;
                String baseName = file.getAbsolutePath();
                // Ensure .tar.lz4 is appended correctly, even if original folder name has dots
                String compressedFileName = baseName.endsWith(File.separator) ? baseName.substring(0, baseName.length() -1) + ".tar.lz4" : baseName + ".tar.lz4";
                
                LogPanel.log("FileSender: Compressing folder " + file.getAbsolutePath() + " to " + compressedFileName);
                archFile = LZ4FileCompressor.compressFolderToTarLz4(file.getAbsolutePath(), compressedFileName);
                LogPanel.log("FileSender: Compressed archive path: " + archFile);
                if (archFile == null || !new File(archFile).exists()) {
                    throw new IOException("Compression failed or compressed file not found: " + compressedFileName);
                }
                currentTotalFileSize = new File(archFile).length();
                // For initial handshake, send the original directory name for user display,
                // but the actual file to be sent later will be archFile.getName()
                handshakeBuilder.append(file.getName()).append(".tar.lz4@"); // Announce the .tar.lz4 name
            } else {
                archFile = file.getAbsolutePath();
                currentTotalFileSize = file.length();
                handshakeBuilder.append(file.getName()).append("@");
            }
            handshakeBuilder.append(THREADS_STR).append("@").append(Long.toString(currentTotalFileSize));
        } catch (IOException e) {
            LogPanel.log("FileSender: Error during file preparation or compression: " + e.getMessage());
            if (callback != null) callback.onError(e);
            if (isCompress && archFile != null) { // Clean up partially compressed file
                new File(archFile).delete();
            }
            return;
        }

        File sentFile = new File(archFile); // This is the file that will be transferred

        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            socket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000);
            String initialHandshakeString = handshakeBuilder.toString();
            
            boolean initialAckReceived = false;
            for (int retries = 0; retries < MAX_HANDSHAKE_RETRIES; retries++) {
                try {
                    LogPanel.log("FileSender: Attempt " + (retries + 1) + ": Sending initial handshake: " + initialHandshakeString);
                    dos.writeUTF(initialHandshakeString);
                    dos.flush();

                    String response = dis.readUTF();
                    LogPanel.log("FileSender: Received response for initial handshake: " + response);
                    if ("ACK".equals(response)) {
                        initialAckReceived = true;
                        break;
                    } else {
                        LogPanel.log("FileSender: Invalid ACK received for initial handshake: " + response);
                    }
                } catch (SocketTimeoutException e) {
                    LogPanel.log("FileSender: Timeout receiving ACK for initial handshake (attempt " + (retries + 1) + ").");
                } catch (IOException e) {
                    LogPanel.log("FileSender: IOException during initial handshake (attempt " + (retries + 1) + "): " + e.getMessage());
                    if (retries == MAX_HANDSHAKE_RETRIES - 1) throw e; // Rethrow on last attempt
                }
                if (!initialAckReceived && retries < MAX_HANDSHAKE_RETRIES - 1) {
                    LogPanel.log("FileSender: Waiting " + HANDSHAKE_TIMEOUT_SECONDS / 2 + "s before retry...");
                    TimeUnit.SECONDS.sleep(HANDSHAKE_TIMEOUT_SECONDS / 2);
                }
            }

            if (!initialAckReceived) {
                throw new IOException("FileSender: Initial handshake failed after " + MAX_HANDSHAKE_RETRIES + " attempts. Receiver did not ACK.");
            }
            LogPanel.log("FileSender: Initial handshake ACKed by receiver.");

            String receiverDecision = dis.readUTF();
            LogPanel.log("FileSender: Received decision from receiver: " + receiverDecision);

            int negotiatedThreadCount = 1;
            boolean transferAcceptedByReceiver = false;

            if (receiverDecision.startsWith("OK@")) {
                try {
                    negotiatedThreadCount = Integer.parseInt(receiverDecision.substring(3)); // Get count after "OK@"
                    negotiatedThreadCount = Math.min(ITHREADS, Math.max(1, negotiatedThreadCount));
                    LogPanel.log("FileSender: Transfer accepted by receiver. Negotiated threads: " + negotiatedThreadCount);
                    
                    LogPanel.log("FileSender: Sending ACK to receiver's OK@ message.");
                    dos.writeUTF("ACK");
                    dos.flush();
                    transferAcceptedByReceiver = true;
                } catch (NumberFormatException | IndexOutOfBoundsException e) {
                    throw new IOException("FileSender: Invalid format in receiver's OK message: " + receiverDecision, e);
                }
            } else if ("REJECT".equals(receiverDecision)) {
                LogPanel.log("FileSender: Transfer rejected by receiver.");
                if (callback != null) callback.onError(new IOException("Transfer rejected by receiver."));
                // Cleanup and return handled by finally block / end of method
            } else {
                throw new IOException("FileSender: Unknown decision from receiver: " + receiverDecision);
            }

            if (transferAcceptedByReceiver) {
                LogPanel.log("FileSender: Sending final filename to receiver: " + sentFile.getName());
                dos.writeUTF(sentFile.getName()); // Send the name of the actual file being transferred
                dos.flush();

                LogPanel.log("FileSender: Waiting for receiver's ACK for the filename...");
                String filenameAck = dis.readUTF(); // This is where the original EOFException occurred (line 168)
                if (!"ACK".equals(filenameAck)) {
                    throw new IOException("FileSender: Receiver did not ACK filename. Received: " + filenameAck);
                }
                LogPanel.log("FileSender: Receiver ACKed filename. Handshake complete. Starting data transfer.");

                if (callback != null) callback.onStart(currentTotalFileSize);
                senderInstance = new SendFile(this.host, this.port, sentFile, negotiatedThreadCount, callback);
                
                Thread senderOperationThread = new Thread(() -> {
                    try {
                        senderInstance.start();
                        // If SendFile.start() completes without throwing an error,
                        // onComplete or onError on the originalCallback should be handled by SendFile itself.
                    } catch (Exception e) {
                        LogPanel.log("FileSender: Exception in SendFile operation thread: " + e.getMessage());
                        if (callback != null) {
                            callback.onError(e);
                        }
                    }
                });
                senderOperationThread.setName("FileSender-SendFile-Operation-Thread");
                senderOperationThread.start(); // Correctly start the thread

                // Note: The original callback.onComplete() was here. It should now be called by SendFile
                // when it truly completes, or onError if SendFile fails.
                // The deletion of the compressed file should happen after senderOperationThread joins,
                // or be handled by a more robust cleanup mechanism if the app can exit before thread completion.
                // For simplicity now, we assume SendFile's callback handles completion/error.
            }
        } catch (IOException e) {
            LogPanel.log("FileSender: IOException during sendFiles: " + e.getMessage());
            // e.printStackTrace();
            if (callback != null) callback.onError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogPanel.log("FileSender: sendFiles interrupted: " + e.getMessage());
            if (callback != null) callback.onError(e);
        } finally {
            if (isCompress && sentFile != null && sentFile.exists() && sentFile.getName().endsWith(".tar.lz4")) {
                // Consider when to delete: if transfer failed, or always after attempting?
                // If SendFile runs in a separate thread, deleting here might be premature.
                // For now, let's assume if an error occurred before SendFile started, we delete.
                // Proper cleanup of temp files often requires more sophisticated state management or shutdown hooks.
                LogPanel.log("FileSender: Cleanup: Compressed file " + sentFile.getAbsolutePath() + " might need deletion depending on transfer status (not deleting automatically here).");
                // new File(archFile).delete(); // Be cautious with auto-deletion if SendFile is async
            }
        }
    }
}