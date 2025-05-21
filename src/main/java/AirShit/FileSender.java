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
    private SendFile senderInstance;
    private final int ITHREADS = Runtime.getRuntime().availableProcessors();
    private final String THREADS_STR = Integer.toString(ITHREADS);

    private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 15; // Default for most operations
    private static final int USER_INTERACTION_TIMEOUT_MINUTES = 5; // Timeout when waiting for user dialog on receiver
    private static final int MAX_INITIAL_HANDSHAKE_RETRIES = 3;


    public FileSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void println(String str) {
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    public void sendFiles(File file, String senderUserName, TransferCallback callback) throws InterruptedException {
        if (file == null) {
            LogPanel.log("FileSender: File to send is null. Aborting.");
            if (callback != null) callback.onError(new IllegalArgumentException("File to send cannot be null."));
            return;
        }

        long currentTotalFileSize = 0;
        StringBuilder handshakeBuilder = new StringBuilder();
        String actualFilePathToSend = null;
        boolean isCompressed = false;
        File fileToSend;

        try {
            if (file.isDirectory()) {
                isCompressed = true;
                String baseName = file.getName(); // Use just the folder name for the archive
                // Place temporary archive in temp directory or a defined local app temp space
                Path tempDir = Files.createTempDirectory("airshit_send_temp");
                String compressedFileName = baseName + ".tar.lz4";
                actualFilePathToSend = Paths.get(tempDir.toString(), compressedFileName).toString();
                
                LogPanel.log("FileSender: Compressing folder " + file.getAbsolutePath() + " to temporary file " + actualFilePathToSend);
                LZ4FileCompressor.compressFolderToTarLz4(file.getAbsolutePath(), actualFilePathToSend); // This should return void or throw
                
                fileToSend = new File(actualFilePathToSend);
                if (!fileToSend.exists() || fileToSend.length() == 0) {
                    throw new IOException("Compression failed or resulted in an empty file: " + actualFilePathToSend);
                }
                currentTotalFileSize = fileToSend.length();
                // Announce the name that the receiver should use for saving (e.g., originalFolderName.tar.lz4)
                handshakeBuilder.append(senderUserName).append("@");
                handshakeBuilder.append(baseName + ".tar.lz4@"); // Name for receiver's dialog & final save name hint
            } else {
                actualFilePathToSend = file.getAbsolutePath();
                fileToSend = file;
                currentTotalFileSize = fileToSend.length();
                handshakeBuilder.append(senderUserName).append("@");
                handshakeBuilder.append(fileToSend.getName()).append("@");
            }
            handshakeBuilder.append(THREADS_STR).append("@").append(currentTotalFileSize);
        } catch (IOException e) {
            LogPanel.log("FileSender: Error during file preparation or compression: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            // e.printStackTrace();
            if (callback != null) callback.onError(e);
            if (isCompressed && actualFilePathToSend != null) {
                try {
                    Files.deleteIfExists(Paths.get(actualFilePathToSend));
                    if (Paths.get(actualFilePathToSend).getParent() != null && Files.isDirectory(Paths.get(actualFilePathToSend).getParent()) && Paths.get(actualFilePathToSend).getParent().getFileName().toString().startsWith("airshit_send_temp")) {
                         Files.deleteIfExists(Paths.get(actualFilePathToSend).getParent()); // Clean up temp dir
                    }
                } catch (IOException ex) {
                    LogPanel.log("FileSender: Error deleting temporary compressed file: " + ex.getMessage());
                }
            }
            return;
        }

        String initialHandshakeString = handshakeBuilder.toString();
        LogPanel.log("FileSender: Prepared initial handshake: " + initialHandshakeString);
        LogPanel.log("FileSender: Actual file to send: " + fileToSend.getAbsolutePath() + ", size: " + currentTotalFileSize);


        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {

            socket.setSoTimeout(DEFAULT_SOCKET_TIMEOUT_SECONDS * 1000);
            
            boolean initialAckReceived = false;
            for (int retries = 0; retries < MAX_INITIAL_HANDSHAKE_RETRIES; retries++) {
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
                        LogPanel.log("FileSender: Invalid ACK received for initial handshake: " + response + ". Retrying if possible...");
                    }
                } catch (SocketTimeoutException e) {
                    LogPanel.log("FileSender: Timeout receiving ACK for initial handshake (attempt " + (retries + 1) + "). Retrying if possible...");
                } catch (IOException e) {
                    LogPanel.log("FileSender: IOException during initial handshake (attempt " + (retries + 1) + "): " + e.getMessage());
                    if (retries == MAX_INITIAL_HANDSHAKE_RETRIES - 1) throw e; 
                }
                if (!initialAckReceived && retries < MAX_INITIAL_HANDSHAKE_RETRIES - 1) {
                    LogPanel.log("FileSender: Waiting " + DEFAULT_SOCKET_TIMEOUT_SECONDS / 2 + "s before retry...");
                    TimeUnit.SECONDS.sleep(DEFAULT_SOCKET_TIMEOUT_SECONDS / 2);
                }
            }

            if (!initialAckReceived) {
                throw new IOException("FileSender: Initial handshake failed after " + MAX_INITIAL_HANDSHAKE_RETRIES + " attempts. Receiver did not ACK.");
            }
            LogPanel.log("FileSender: Initial handshake ACKed by receiver.");

            // Set a longer timeout for waiting for the receiver's decision (OK@ or REJECT)
            int originalTimeoutMillis = socket.getSoTimeout();
            socket.setSoTimeout(USER_INTERACTION_TIMEOUT_MINUTES * 60 * 1000); 
            LogPanel.log("FileSender: Set timeout to " + USER_INTERACTION_TIMEOUT_MINUTES + " minutes waiting for receiver's decision (OK@/REJECT).");

            String receiverDecision;
            try {
                receiverDecision = dis.readUTF(); // Wait for "OK@..." or "REJECT"
            } finally {
                socket.setSoTimeout(originalTimeoutMillis); // Restore original timeout
                LogPanel.log("FileSender: Restored timeout to " + originalTimeoutMillis / 1000 + "s.");
            }
            
            LogPanel.log("FileSender: Received decision from receiver: " + receiverDecision);

            int negotiatedThreadCount = 1;
            boolean transferAcceptedByReceiver = false;

            if (receiverDecision.startsWith("OK@")) {
                try {
                    negotiatedThreadCount = Integer.parseInt(receiverDecision.substring(3));
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
            } else {
                throw new IOException("FileSender: Unknown decision from receiver: " + receiverDecision);
            }

            if (transferAcceptedByReceiver) {
                // Send the name of the file that is ACTUALLY being sent (e.g., the .tar.lz4 name if compressed)
                String finalFileNameToSend = fileToSend.getName();
                LogPanel.log("FileSender: Sending final filename to receiver: " + finalFileNameToSend);
                dos.writeUTF(finalFileNameToSend); 
                dos.flush();

                LogPanel.log("FileSender: Waiting for receiver's ACK for the filename...");
                String filenameAck = dis.readUTF(); 
                if (!"ACK".equals(filenameAck)) {
                    throw new IOException("FileSender: Receiver did not ACK filename. Received: " + filenameAck);
                }
                LogPanel.log("FileSender: Receiver ACKed filename. Handshake complete. Starting data transfer.");

                if (callback != null) callback.onStart(currentTotalFileSize);
                senderInstance = new SendFile(this.host, this.port, fileToSend, negotiatedThreadCount, callback);
                
                Thread senderOperationThread = new Thread(() -> {
                    try {
                        senderInstance.start();
                    } catch (Exception e) {
                        LogPanel.log("FileSender: Exception in SendFile operation thread: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                        // e.printStackTrace();
                        if (callback != null) {
                            callback.onError(e);
                        }
                    } finally {
                        if (isCompressed && actualFilePathToSend != null) {
                            LogPanel.log("FileSender: Deleting temporary compressed file: " + actualFilePathToSend);
                            try {
                                Files.deleteIfExists(Paths.get(actualFilePathToSend));
                                Path parentDir = Paths.get(actualFilePathToSend).getParent();
                                if (parentDir != null && Files.isDirectory(parentDir) && parentDir.getFileName().toString().startsWith("airshit_send_temp")) {
                                     Files.deleteIfExists(parentDir); // Clean up temp dir if it's ours
                                     LogPanel.log("FileSender: Deleted temporary directory: " + parentDir);
                                }
                            } catch (IOException ex) {
                                LogPanel.log("FileSender: Error deleting temporary compressed file/dir: " + ex.getMessage());
                            }
                        }
                    }
                });
                senderOperationThread.setName("FileSender-SendFile-Operation-Thread");
                senderOperationThread.start();
            }
        } catch (IOException e) {
            LogPanel.log("FileSender: IOException during sendFiles: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            // e.printStackTrace();
            if (callback != null) callback.onError(e);
            // Ensure temp compressed file is cleaned up on outer error too
            if (isCompressed && actualFilePathToSend != null) {
                 LogPanel.log("FileSender: Cleaning up temporary compressed file due to error: " + actualFilePathToSend);
                 try {
                     Files.deleteIfExists(Paths.get(actualFilePathToSend));
                     Path parentDir = Paths.get(actualFilePathToSend).getParent();
                     if (parentDir != null && Files.isDirectory(parentDir) && parentDir.getFileName().toString().startsWith("airshit_send_temp")) {
                          Files.deleteIfExists(parentDir);
                     }
                 } catch (IOException ex) {
                     LogPanel.log("FileSender: Error deleting temporary compressed file on error: " + ex.getMessage());
                 }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogPanel.log("FileSender: sendFiles interrupted: " + e.getMessage());
            if (callback != null) callback.onError(e);
            if (isCompressed && actualFilePathToSend != null) {
                 LogPanel.log("FileSender: Cleaning up temporary compressed file due to interruption: " + actualFilePathToSend);
                 try {
                     Files.deleteIfExists(Paths.get(actualFilePathToSend));
                     Path parentDir = Paths.get(actualFilePathToSend).getParent();
                     if (parentDir != null && Files.isDirectory(parentDir) && parentDir.getFileName().toString().startsWith("airshit_send_temp")) {
                          Files.deleteIfExists(parentDir);
                     }
                 } catch (IOException ex) {
                     LogPanel.log("FileSender: Error deleting temporary compressed file on interruption: " + ex.getMessage());
                 }
            }
        }
    }
}