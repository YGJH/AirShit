package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        callback.onStart(0);
        long currentTotalFileSize = 0;
        StringBuilder handshakeBuilder = new StringBuilder();
        String actualFilePathToSend = null;
        boolean isCompressed = false;
        File[] fileToSend = new File[1000000];
        int fileCount = 0;

        try {
            if (file.isDirectory()) {
                isCompressed = true;
                String baseName = file.getName(); // Use just the folder name for the archive
                // Place temporary archive in temp directory or a defined local app temp space
                Path tempDir = Files.createTempDirectory("airshit_send_temp");
                String compressedFileName = baseName + ".tar.lz4";
                actualFilePathToSend = Paths.get(tempDir.toString(), compressedFileName).toString();               
                fileCount = LZ4FileCompressor.compressFolderToTarLz4(file.getAbsolutePath(), actualFilePathToSend , fileToSend); // This should return void or throw
                if(fileCount == 0 && new File(actualFilePathToSend).length() == 0) { // If no large files AND archive is empty (or failed)
                    return ;
                }
                currentTotalFileSize = 0; // Recalculate based on what will be sent
                handshakeBuilder.append(senderUserName).append("@");
                for(int i = 0 ; i < fileCount ; i++) { // Names of large files
                    handshakeBuilder.append(fileToSend[i].getName()).append("@"); // Use char for single separator
                    currentTotalFileSize += fileToSend[i].length();
                }
                System.out.println(currentTotalFileSize);
                // Add the archive name to handshake and its size to total
                if (actualFilePathToSend != null) { // Ensure archive path exists before using it
                    handshakeBuilder.append(new File(actualFilePathToSend).getName()).append("@"); 
                    currentTotalFileSize += new File(actualFilePathToSend).length();
                } else if (isCompressed) {
                    // This case (isCompressed but actualFilePathToSend is null) might indicate an earlier error
                    // or an empty directory that resulted in no archive. Handle as per requirements.
                    // For now, just ensure we don't try to use a null actualFilePathToSend.
                    LogPanel.log("FileSender: Warning - isCompressed is true, but actualFilePathToSend is null during handshake build.");
                }


            } else { // Single file case
                actualFilePathToSend = null; // Not compressing a single file directly with tar.lz4 in this path
                fileToSend = new File[]{file}; // The single file itself
                fileCount = 1;
                isCompressed = false; // It's a single file, not a compressed directory archive from this class's perspective
                currentTotalFileSize = fileToSend[0].length();
                handshakeBuilder.append(senderUserName).append("@");
                handshakeBuilder.append(fileToSend[0].getName()).append("@");
            }
            handshakeBuilder.append(THREADS_STR).append("@").append(currentTotalFileSize);
        } catch (IOException e) {
            LogPanel.log("FileSender: Error during file preparation or compression: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            // e.printStackTrace();
            if (callback != null) {
                callback.onError(e); // Call onError only once
            }
            // 清理臨時檔案的邏輯：
            // 如果是因為處理目錄 (isCompressed == true) 而創建了臨時 .tar.lz4 檔案 (actualFilePathToSend != null)
            // 並且在這個階段發生了錯誤，則需要清理這些臨時檔案。
            if (fileToSend != null) {
                LogPanel.log("FileSender: Cleaning up temporary compressed file (.tar.lz4) due to preparation error: " + actualFilePathToSend);
                try {
                    Path tempArchiveFile = Paths.get(actualFilePathToSend); // actualFilePathToSend 就是 .tar.lz4 檔案的路徑
                    
                    // 嘗試刪除 .tar.lz4 檔案
                    boolean deletedArchive = Files.deleteIfExists(tempArchiveFile);
                    if (deletedArchive) {
                        LogPanel.log("FileSender: Successfully deleted temporary .tar.lz4 file: " + actualFilePathToSend);
                    } else {
                        // 如果 actualFilePathToSend 存在但刪除失敗，Files.deleteIfExists 會拋出 IOException
                        // 如果檔案本來就不存在，則 deletedArchive 為 false，且不會拋錯。
                        // 這裡可以根據需要添加日誌，但通常 deleteIfExists 的行為已經包含了不存在的情況。
                        LogPanel.log("FileSender: Temporary .tar.lz4 file did not exist or was not deleted by this operation (may have failed if in use): " + actualFilePathToSend);
                    }

                    Path tempDir = tempArchiveFile.getParent();
                    // 確保父目錄是我們創建的臨時目錄
                    if (tempDir != null && Files.isDirectory(tempDir) && tempDir.getFileName().toString().startsWith("airshit_send_temp")) {
                        // 嘗試刪除臨時目錄 (通常在 .tar.lz4 檔案被刪除後，這個目錄應該是空的)
                        boolean deletedDir = Files.deleteIfExists(tempDir); 
                        if (deletedDir) {
                            LogPanel.log("FileSender: Successfully deleted temporary directory: " + tempDir);
                        } else {
                            LogPanel.log("FileSender: Temporary directory did not exist or was not deleted (may not be empty or access issue): " + tempDir);
                        }
                    }
                } catch (IOException ex) {
                    LogPanel.log("FileSender: Error during cleanup of temporary compressed file/dir (" + actualFilePathToSend + "): " + ex.getMessage());
                }
            }
            return; // 處理完錯誤後退出 sendFiles 方法
        }

        String initialHandshakeString = handshakeBuilder.toString();

        LogPanel.log("FileSender: Prepared initial handshake: " + initialHandshakeString);

        if (fileCount > 0) {
            if (!isCompressed) { // Single file
                 LogPanel.log("FileSender: File to send: " + fileToSend[0].getAbsolutePath() + ", total size: " + currentTotalFileSize);
            } else { // Directory with large files and potentially an archive
                 LogPanel.log("FileSender: Preparing to send " + fileCount + " large file(s) and an archive. Total size: " + currentTotalFileSize);
                 // For more detail, you could list fileToSend[0] if it's the first of many, or the archive path.
                 // Example: LogPanel.log("FileSender: First large file: " + fileToSend[0].getAbsolutePath());
                 // if (actualFilePathToSend != null) LogPanel.log("FileSender: Archive file: " + actualFilePathToSend);
            }
        } else if (isCompressed && actualFilePathToSend != null) { // Empty directory resulting in only an archive
            LogPanel.log("FileSender: File to send (archive of small/empty dir): " + actualFilePathToSend + ", total size: " + currentTotalFileSize);
        } else if (isCompressed) { // Empty directory, no archive created (should ideally not happen if compression is attempted)
            LogPanel.log("FileSender: Sending an empty directory (no large files, no archive generated). Total size: " + currentTotalFileSize);
        } else { // Should not happen if fileCount is 0 and not isCompressed
            LogPanel.log("FileSender: No files to send. Total size: " + currentTotalFileSize);
        }


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
                for(int i = 0 ; i < fileCount ; i++) {
                    String finalFileNameToSend = fileToSend[i].getName();
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
                    senderInstance = new SendFile(this.host, this.port, fileToSend[i], negotiatedThreadCount, callback);
                    
                    // Create final copies of variables to be used in the lambda
                    final boolean lambdaIsCompressed = isCompressed;
                    final String lambdaActualFilePathToSend = actualFilePathToSend;

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
                            // Use the final copies within the lambda
                            if (lambdaIsCompressed && lambdaActualFilePathToSend != null) {
                                LogPanel.log("FileSender: Deleting temporary compressed file: " + lambdaActualFilePathToSend);
                                try {
                                    Path tempFileToDeletePath = Paths.get(lambdaActualFilePathToSend);
                                    Files.deleteIfExists(tempFileToDeletePath);
                                    Path parentDir = tempFileToDeletePath.getParent();
                                    // Ensure parentDir is not null and is the temp directory we created
                                    if (parentDir != null && Files.isDirectory(parentDir) && parentDir.getFileName().toString().startsWith("airshit_send_temp")) {
                                        try {
                                            Files.deleteIfExists(parentDir); // Attempt to delete the directory.
                                            LogPanel.log("FileSender: Deleted temporary directory: " + parentDir);
                                        } catch (IOException exDelDir) {
                                            // Log if directory deletion fails (e.g., not empty, permissions)
                                            LogPanel.log("FileSender: Could not delete temporary directory (it might not be empty or access issue): " + parentDir + " - " + exDelDir.getMessage());
                                        }
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