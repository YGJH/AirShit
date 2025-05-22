package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import AirShit.ui.*;
import AirShit.ui.LogPanel;

public class FileSender {
    private String host;
    private int port;
    private SendFile senderInstance; // Instance for sending a single file's data
    private final int ITHREADS = Runtime.getRuntime().availableProcessors();
    private final String THREADS_STR = Integer.toString(ITHREADS);

    private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 15;
    private static final int USER_INTERACTION_TIMEOUT_MINUTES = 5;
    private static final int MAX_INITIAL_HANDSHAKE_RETRIES = 3; // Not used in the new protocol directly, but good to keep for other potential retries

    public FileSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void println(String str) {
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    public void sendFiles(File inputFile, String senderUserName, TransferCallback callback) {
        if (inputFile == null) {
            LogPanel.log("FileSender: Input file/directory is null. Aborting.");
            if (callback != null) callback.onError(new IllegalArgumentException("Input file/directory cannot be null."));
            return;
        }
        // callback.onStart(0); // Moved to after total size is known

        List<File> filesToProcess = new ArrayList<>();
        String tempArchiveFilePath = null; // Path to the temporary .tar.lz4 archive, if created
        boolean isDirectoryTransfer = inputFile.isDirectory();
        long totalSizeOverall = 0;
        Path tempDirForArchive = null; // To store the path of the temp directory for the archive

        // Outer try-finally for cleaning up the temporary archive and its directory, if created.
        try {
            LogPanel.log("FileSender: Preparing files for sending...");
            if (isDirectoryTransfer) {
                isCompressed = true; // Legacy variable, indicates directory processing
                String baseName = inputFile.getName();
                tempDirForArchive = Files.createTempDirectory("airshit_send_temp_"); // Create unique temp dir
                String compressedFileName = baseName + ".tar.lz4";
                tempArchiveFilePath = Paths.get(tempDirForArchive.toString(), compressedFileName).toString();

                // LZ4FileCompressor will populate largeFilesArray with files >= 3MB
                // and create tempArchiveFilePath with files < 3MB.
                File[] largeFilesArray = new File[1000000]; // Max large files, adjust if necessary
                int largeFileCount = LZ4FileCompressor.compressFolderToTarLz4(inputFile.getAbsolutePath(), tempArchiveFilePath, largeFilesArray);

                for (int i = 0; i < largeFileCount; i++) {
                    if (largeFilesArray[i] != null && largeFilesArray[i].exists()) {
                        filesToProcess.add(largeFilesArray[i]);
                        totalSizeOverall += largeFilesArray[i].length();
                    }
                }

                File archiveFile = new File(tempArchiveFilePath);
                if (archiveFile.exists() && archiveFile.length() > 0) {
                    filesToProcess.add(archiveFile);
                    totalSizeOverall += archiveFile.length();
                } else if (largeFileCount == 0) { // No large files and no (or empty) archive
                    LogPanel.log("FileSender: Directory '" + inputFile.getName() + "' is empty or resulted in no files to send.");
                    if (archiveFile.exists()) Files.deleteIfExists(archiveFile.toPath()); // Clean up empty archive
                    // tempDirForArchive will be cleaned in the final finally block
                    if (callback != null) callback.onComplete(); // Or onError if this is an error
                    return;
                }
                LogPanel.log("FileSender: Directory processing complete. Large files: " + largeFileCount + ". Archive: " + (archiveFile.exists() && archiveFile.length() > 0 ? archiveFile.getName() : "N/A"));

            } else { // Single file
                isCompressed = false; // Legacy variable
                filesToProcess.add(inputFile);
                totalSizeOverall += inputFile.length();
                LogPanel.log("FileSender: Single file selected: " + inputFile.getName());
            }

            if (filesToProcess.isEmpty()) {
                LogPanel.log("FileSender: No files to send after preparation.");
                if (callback != null) callback.onComplete();
                return;
            }

            LogPanel.log("FileSender: Total files to send: " + filesToProcess.size() + ", Total size: " + SendFileGUI.formatFileSize(totalSizeOverall));

            // Initial Handshake: SENDER_USERNAME@NUMBER_OF_FILES_TO_SEND@TOTAL_SIZE_BYTES@REQUESTED_THREADS@IS_DIRECTORY@ORIGINAL_FOLDER_NAME
            String originalFolderName = isDirectoryTransfer ? inputFile.getName() : "-"; // Use "-" for single file
            String initialMetadata = senderUserName + "@" +
                                     filesToProcess.size() + "@" +
                                     totalSizeOverall + "@" +
                                     THREADS_STR + "@" +
                                     (isDirectoryTransfer ? "1" : "0") + "@" +
                                     originalFolderName;

            try (Socket socket = new Socket(host, port); // Line ~106 in your provided full FileSender
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                 DataInputStream dis = new DataInputStream(socket.getInputStream())) {

                socket.setSoTimeout(DEFAULT_SOCKET_TIMEOUT_SECONDS * 1000); // Set timeout immediately after socket creation

                LogPanel.log("FileSender: Sending initial metadata: " + initialMetadata);
                dos.writeUTF(initialMetadata);
                dos.flush();

                String ackResponse = dis.readUTF(); // Line ~117 - EOFException occurs here
                if (!"ACK_METADATA".equals(ackResponse)) {
                    throw new IOException("FileSender: Did not receive ACK_METADATA. Got: " + ackResponse);
                }
                LogPanel.log("FileSender: Received ACK_METADATA.");

                // ===== 階段 2: 檔案資訊迴圈 =====
                for (File fileToSendInfo : filesToProcess) {
                    String fileInfoString = fileToSendInfo.getName() + "@" + fileToSendInfo.length();
                    LogPanel.log("FileSender: Sending file info: " + fileInfoString);
                    dos.writeUTF(fileInfoString);
                    dos.flush();
                    String fileInfoAck = dis.readUTF();
                    if (!"ACK_FILE_INFO".equals(fileInfoAck)) {
                        throw new IOException("FileSender: Did not receive ACK_FILE_INFO for " + fileToSendInfo.getName() + ". Got: " + fileInfoAck);
                    }
                    LogPanel.log("FileSender: Received ACK_FILE_INFO for " + fileToSendInfo.getName());
                }

                // ===== 階段 3: 等待接收方決定 =====
                // Wait for receiver's decision (OK@ or REJECT)
                int originalTimeoutMillis = socket.getSoTimeout();
                socket.setSoTimeout(USER_INTERACTION_TIMEOUT_MINUTES * 60 * 1000);
                LogPanel.log("FileSender: Set timeout to " + USER_INTERACTION_TIMEOUT_MINUTES + " minutes waiting for receiver's decision (OK@/REJECT).");
                String receiverDecision;
                try {
                    receiverDecision = dis.readUTF();
                } finally {
                    socket.setSoTimeout(originalTimeoutMillis);
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
                        dos.writeUTF("ACK_DECISION"); // Sender ACKs the OK
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

                // ===== 階段 4: 資料傳輸 (如果接受) =====
                if (transferAcceptedByReceiver) {
                    if (callback != null) callback.onStart(totalSizeOverall); // Notify UI with total size

                    for (File fileToActuallySend : filesToProcess) {
                        LogPanel.log("FileSender: Starting transfer for: " + fileToActuallySend.getName() + " (" + SendFileGUI.formatFileSize(fileToActuallySend.length()) + ")");
                        
                        senderInstance = new SendFile(this.host, this.port, fileToActuallySend, negotiatedThreadCount, callback);
                        
                        // Run SendFile operation in a new thread to keep UI responsive, but wait for it to finish before next file.
                        // For true parallel file sending, SendFile and Receiver would need significant redesign.
                        final File currentFileForThread = fileToActuallySend; // Effectively final for lambda
                        Thread senderOperationThread = new Thread(() -> {
                            try {
                                senderInstance.start(); // This blocks until this file is sent or error
                            } catch (Exception e) {
                                LogPanel.log("FileSender: Exception in SendFile operation for " + currentFileForThread.getName() + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                                if (callback != null) {
                                    // It's tricky to call onError multiple times. Consider a single overall failure.
                                    // For now, let individual SendFile errors be handled by its callback.
                                }
                            }
                        });
                        senderOperationThread.setName("FileSender-Op-" + currentFileForThread.getName());
                        senderOperationThread.start();
                        senderOperationThread.join(); // Wait for the current file to finish sending

                        LogPanel.log("FileSender: Finished SendFile operation for: " + currentFileForThread.getName());
                    }
                    LogPanel.log("FileSender: All files processed for sending.");
                    if (callback != null) callback.onComplete();
                }
            } // End of try-with-resources for Socket, dos, dis
        } catch (IOException e) {
            LogPanel.log("FileSender: IOException during sendFiles: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (callback != null) callback.onError(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LogPanel.log("FileSender: sendFiles interrupted: " + e.getMessage());
            if (callback != null) callback.onError(e);
        } finally {
            // Final cleanup for the temporary archive file and its directory
            if (isDirectoryTransfer && tempArchiveFilePath != null) {
                LogPanel.log("FileSender: Final cleanup: Attempting to delete temporary archive: " + tempArchiveFilePath);
                try {
                    Path archivePath = Paths.get(tempArchiveFilePath);
                    // tempDirForArchive was stored earlier
                    
                    Files.deleteIfExists(archivePath);
                    LogPanel.log("FileSender: Final cleanup: Attempted delete of archive file " + archivePath.getFileName());

                    if (tempDirForArchive != null && Files.exists(tempDirForArchive)) {
                        Files.deleteIfExists(tempDirForArchive);
                        LogPanel.log("FileSender: Final cleanup: Attempted delete of temporary directory: " + tempDirForArchive);
                    }
                } catch (IOException ex) {
                    LogPanel.log("FileSender: Final cleanup: Error deleting temporary archive/dir: " + ex.getMessage());
                }
            }
        }
    }
    // Legacy isCompressed variable, not strictly needed with the new List<File> approach
    private boolean isCompressed = false;
}