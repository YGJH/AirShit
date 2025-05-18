package AirShit;

import java.awt.Dimension;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import AirShit.Main.SEND_STATUS;
import AirShit.ui.LogPanel;

/**
 * Receiver: 在指定埠口接收多個執行緒送過來的檔案分段，並寫入同一個檔案中。
 */
public class FileReceiver {

    public int port;
    private static final String COMPRESSED_ARCHIVE_PREFIX = "AirShit_Archive_"; // From FileSender

    FileReceiver(int port) {
        this.port = port;
    }

    public static void println(String str) {
        // System.out.println(str); // Replaced by LogPanel
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    public void start(TransferCallback cb) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        LogPanel.log("FileReceiver started on port: " + port);

        while (true) {
            boolean isSingleFileHandshake = false;
            String senderUserName = null;
            String originalSingleFileName = null; // For single file, the name from handshake
            String originalFolderName = null;     // For multi-file, the folder name from handshake
            int expectedFileTransfers = 0;      // Number of actual transfer operations expected (1 if compressed, N otherwise)
            long totalOriginalSize = 0;
            String totalOriginalSizeStr = null;
            StringBuilder fileListForDialog = new StringBuilder();

            // Outer try-with-resources for the initial handshake socket
            try (Socket handshakeSocket = serverSocket.accept();
                 DataInputStream dis = new DataInputStream(handshakeSocket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(handshakeSocket.getOutputStream())) {

                LogPanel.log("Accepted connection from: " + handshakeSocket.getInetAddress());

                if (Main.sendStatus != null && Main.SEND_STATUS.SEND_WAITING == Main.sendStatus.get()) {
                    LogPanel.log("Receiver busy, rejecting new connection from " + handshakeSocket.getInetAddress());
                    dos.writeUTF("REJECT_BUSY");
                    dos.flush();
                    continue; // Next iteration of while(true)
                }

                String handshakeMessage = dis.readUTF();
                LogPanel.log("Received handshake message: " + handshakeMessage);
                String[] parts = handshakeMessage.split("\\|");

                int minPartsSingle = 6; // type|user|name|size|sizeStr|threads
                int minPartsMultiBase = 6; // type|user|folder|totalSize|totalSizeStr|threads (plus at least one file)

                int clientAnnouncedThreads;

                if ("isSingle".equals(parts[0])) {
                    if (parts.length < minPartsSingle) {
                        LogPanel.log("Invalid handshake (isSingle, too few parts): " + handshakeMessage);
                        dos.writeUTF("REJECT_BAD_HANDSHAKE_FORMAT");
                        dos.flush();
                        continue;
                    }
                    isSingleFileHandshake = true;
                    senderUserName = parts[1];
                    originalSingleFileName = parts[2]; // This is the original file name
                    try {
                        totalOriginalSize = Long.parseLong(parts[3]);
                        totalOriginalSizeStr = parts[4];
                        clientAnnouncedThreads = Integer.parseInt(parts[5]);
                    } catch (NumberFormatException e) {
                        LogPanel.log("Invalid number format in handshake (isSingle): " + handshakeMessage);
                        dos.writeUTF("REJECT_BAD_HANDSHAKE_DATA");
                        dos.flush();
                        continue;
                    }
                    fileListForDialog.append(originalSingleFileName);
                    expectedFileTransfers = 1; // Single file always means one transfer operation
                } else if ("isMulti".equals(parts[0])) {
                    if (parts.length < minPartsMultiBase + 1) { // Must have at least one file name
                        LogPanel.log("Invalid handshake (isMulti, too few parts or no files): " + handshakeMessage);
                        dos.writeUTF("REJECT_BAD_HANDSHAKE_FORMAT");
                        dos.flush();
                        continue;
                    }
                    isSingleFileHandshake = false;
                    senderUserName = parts[1];
                    originalFolderName = parts[2]; // This is the original folder name
                    // Files are from parts[3] up to parts[parts.length - 4]
                    // parts[parts.length - 3] is totalSize
                    // parts[parts.length - 2] is totalSizeStr
                    // parts[parts.length - 1] is threads
                    try {
                        totalOriginalSize = Long.parseLong(parts[parts.length - 3]);
                        totalOriginalSizeStr = parts[parts.length - 2];
                        clientAnnouncedThreads = Integer.parseInt(parts[parts.length - 1]);
                    } catch (NumberFormatException e) {
                        LogPanel.log("Invalid number format in handshake (isMulti): " + handshakeMessage);
                        dos.writeUTF("REJECT_BAD_HANDSHAKE_DATA");
                        dos.flush();
                        continue;
                    }
                    for (int i = 3; i < parts.length - 3; i++) {
                        fileListForDialog.append(parts[i]).append("\n");
                    }
                    // For multi-file, initially assume one transfer per file.
                    // This will be overridden if the sender indicates compression for the *batch*.
                    expectedFileTransfers = (parts.length - 3) - 3;
                } else {
                    LogPanel.log("Invalid handshake type: " + parts[0]);
                    dos.writeUTF("REJECT_BAD_HANDSHAKE_TYPE");
                    dos.flush();
                    continue;
                }

                String dialogInfo = "Sender: " + senderUserName
                        + (isSingleFileHandshake ? "\nFile: " + originalSingleFileName : "\nFolder: " + (originalFolderName.isEmpty() ? "(Root)" : originalFolderName))
                        + "\nTotal Original Size: " + totalOriginalSizeStr
                        + "\n\nFiles (as announced by sender):\n";
                JTextArea ta = new JTextArea(dialogInfo + fileListForDialog.toString().trim());
                ta.setEditable(false);
                ta.setLineWrap(true);
                ta.setWrapStyleWord(true);
                JScrollPane pane = new JScrollPane(ta, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                pane.setPreferredSize(new Dimension(400, 200));

                int userResponse = JOptionPane.showConfirmDialog(null, pane, "File Transfer - Confirm Reception", JOptionPane.YES_NO_OPTION);
                if (userResponse != JOptionPane.YES_OPTION) {
                    dos.writeUTF("REJECT_USER");
                    dos.flush();
                    LogPanel.log("User rejected file reception.");
                    continue;
                }

                String selectedRootPath = FolderSelector.selectFolder();
                if (selectedRootPath == null) {
                    dos.writeUTF("REJECT_NO_FOLDER_SELECTED");
                    dos.flush();
                    LogPanel.log("User cancelled folder selection.");
                    continue;
                }

                // Determine the final base path for saving files
                File finalSaveBasePath;
                if (!isSingleFileHandshake && originalFolderName != null && !originalFolderName.isEmpty()) {
                    finalSaveBasePath = new File(selectedRootPath, originalFolderName);
                } else {
                    finalSaveBasePath = new File(selectedRootPath);
                }

                if (!finalSaveBasePath.exists()) {
                    if (!finalSaveBasePath.mkdirs()) {
                        LogPanel.log("Error: Could not create destination directory: " + finalSaveBasePath.getAbsolutePath());
                        dos.writeUTF("REJECT_DEST_DIR_CREATE_FAIL");
                        dos.flush();
                        continue;
                    }
                    LogPanel.log("Created destination directory: " + finalSaveBasePath.getAbsolutePath());
                }


                final int receiverThreadCount = Math.min(clientAnnouncedThreads, Runtime.getRuntime().availableProcessors());
                dos.writeUTF("ACK|" + receiverThreadCount); // Acknowledge handshake and send back effective thread count
                dos.flush();

                cb.onStart(totalOriginalSize); // Callback with the original total size

                // Loop for each expected file transfer operation
                // If sender compresses a batch, it sends 1 archive, so this loop should effectively run once for that batch.
                for (int transferOpIndex = 0; transferOpIndex < expectedFileTransfers; transferOpIndex++) {
                    String currentReceivedFileName = null; // Name of the file/archive being received in this op
                    long currentReceivedFileSize = 0;
                    boolean isThisTransferACompressedArchive = false;

                    // Inner try-with-resources for the socket handling this specific file/archive transfer
                    try (Socket fileDataSocket = serverSocket.accept(); // Expect a new connection for each file/archive
                         DataInputStream fileDis = new DataInputStream(fileDataSocket.getInputStream());
                         DataOutputStream fileDos = new DataOutputStream(fileDataSocket.getOutputStream())) {

                        String fileMetadata = fileDis.readUTF(); // Expect "fileName|fileSize|isCompressedSuccessfully"
                        LogPanel.log("Received file metadata: " + fileMetadata);
                        String[] metaParts = fileMetadata.split("\\|");

                        if (metaParts.length < 2 || metaParts.length > 3) { // Min 2 (name, size), Max 3 (name, size, isCompressed)
                            LogPanel.log("Error: Invalid file metadata received: " + fileMetadata);
                            fileDos.writeUTF("ERROR_BAD_METADATA");
                            fileDos.flush();
                            // This error is for the current file transfer, might need to abort the whole session.
                            // For now, break this inner loop and the outer loop will eventually call onComplete.
                            throw new IOException("Bad file metadata from sender: " + fileMetadata);
                        }

                        currentReceivedFileName = metaParts[0];
                        try {
                            currentReceivedFileSize = Long.parseLong(metaParts[1]);
                        } catch (NumberFormatException e) {
                            LogPanel.log("Error: Invalid file size in metadata: " + metaParts[1]);
                            fileDos.writeUTF("ERROR_BAD_FILE_SIZE");
                            fileDos.flush();
                            throw new IOException("Bad file size from sender: " + metaParts[1], e);
                        }

                        if (metaParts.length == 3) {
                            isThisTransferACompressedArchive = Boolean.parseBoolean(metaParts[2]);
                        }
                        
                        // If this is a compressed archive for a multi-file scenario, it represents all remaining files.
                        if (isThisTransferACompressedArchive && !isSingleFileHandshake) {
                            expectedFileTransfers = transferOpIndex + 1; // This is the last transfer operation
                            LogPanel.log("Receiving a compressed archive for a multi-file transfer. This will be the only transfer operation.");
                        }


                        // Sanitize received filename to prevent path traversal when creating the file object
                        Path targetFilePath;
                        try {
                            // Normalize to prevent "file/../../file" type issues within the name itself
                            String sanitizedFileName = Paths.get(currentReceivedFileName).normalize().getFileName().toString();
                            if (sanitizedFileName.isEmpty() || sanitizedFileName.equals(".") || sanitizedFileName.equals("..")) {
                                throw new InvalidPathException(currentReceivedFileName, "Invalid/unsafe file name component");
                            }
                            targetFilePath = finalSaveBasePath.toPath().resolve(sanitizedFileName);

                            // Double check it's still within the intended save base path
                            if (!targetFilePath.toAbsolutePath().normalize().startsWith(finalSaveBasePath.toPath().toAbsolutePath().normalize())) {
                                 LogPanel.log("Security Alert: Attempt to write file outside designated save path (post-sanitize): " + targetFilePath);
                                 throw new SecurityException("Path traversal attempt detected in file name: " + currentReceivedFileName);
                            }

                        } catch (InvalidPathException | SecurityException e) {
                            LogPanel.log("Error: Invalid or unsafe file name received: " + currentReceivedFileName + " - " + e.getMessage());
                            fileDos.writeUTF("ERROR_INVALID_FILENAME");
                            fileDos.flush();
                            throw new IOException("Invalid file name from sender: " + currentReceivedFileName, e);
                        }


                        File targetFile = targetFilePath.toFile();
                        if (targetFile.exists()) {
                            LogPanel.log("Warning: File " + targetFile.getAbsolutePath() + " already exists. Overwriting.");
                            if (!targetFile.delete()) {
                                LogPanel.log("Warning: Failed to delete existing file before overwrite: " + targetFile.getAbsolutePath());
                            }
                        }

                        File parentDir = targetFile.getParentFile();
                        if (parentDir != null && !parentDir.exists()) {
                            if (!parentDir.mkdirs()) {
                                LogPanel.log("Error: Could not create parent directory for file: " + parentDir.getAbsolutePath());
                                fileDos.writeUTF("ERROR_PARENT_DIR_CREATE");
                                fileDos.flush();
                                throw new IOException("Failed to create parent directory: " + parentDir.getAbsolutePath());
                            }
                        }
                        if (!targetFile.createNewFile()) {
                             LogPanel.log("Error: Could not create target file (already exists or other issue): " + targetFile.getAbsolutePath());
                             // This might happen if delete failed and it's a directory, or permissions issue
                        }


                        fileDos.writeUTF("ACK"); // Acknowledge metadata, ready for file data
                        fileDos.flush();
                        LogPanel.log("Ready to receive data for: " + currentReceivedFileName + ", Size: " + SendFileGUI.formatFileSize(currentReceivedFileSize));


                        ExecutorService executor = Executors.newSingleThreadExecutor();
                        Receiver actualFileReceiver = new Receiver(serverSocket); // The class that handles chunked receiving
                        Future<Boolean> future;
                        final String fullPathForReceiver = targetFile.getAbsolutePath();
                        final long sizeForReceiver = currentReceivedFileSize;
                        final int threadsForThisFile = (sizeForReceiver < 6 * 1024 * 1024) ? 1 : receiverThreadCount;

                        future = executor.submit(() -> {
                            try {
                                return actualFileReceiver.start(fullPathForReceiver, sizeForReceiver, threadsForThisFile, cb);
                            } catch (IOException e) {
                                LogPanel.log("IOException in Receiver.start for " + fullPathForReceiver + ": " + e.getMessage());
                                return false;
                            }
                        });

                        boolean success;
                        try {
                            success = future.get();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            LogPanel.log("File reception interrupted for: " + currentReceivedFileName);
                            success = false;
                        } catch (ExecutionException ee) {
                            LogPanel.log("ExecutionException during file reception for: " + currentReceivedFileName + " - " + ee.getCause().getMessage());
                            success = false;
                        } finally {
                            executor.shutdown();
                        }

                        if (success) {
                            LogPanel.log("File " + currentReceivedFileName + " received successfully. Size: " + SendFileGUI.formatFileSize(currentReceivedFileSize));
                            if (isThisTransferACompressedArchive) {
                                LogPanel.log("Attempting to decompress archive: " + currentReceivedFileName);
                                Decompresser decompresser = new Decompresser(); // Create instance here
                                if (decompresser.decompressFile(fullPathForReceiver, finalSaveBasePath.getAbsolutePath())) {
                                    LogPanel.log("Successfully decompressed. Deleting archive: " + currentReceivedFileName);
                                    if (!targetFile.delete()) {
                                        LogPanel.log("Warning: Failed to delete archive " + currentReceivedFileName);
                                    }
                                } else {
                                    LogPanel.log("Decompression failed for " + currentReceivedFileName + ". Archive remains.");
                                    // Decide if this is a critical error for the whole transfer
                                }
                            }
                            fileDos.writeUTF("OK");
                            fileDos.flush();
                        } else {
                            LogPanel.log("Error: File reception failed for: " + currentReceivedFileName);
                            fileDos.writeUTF("ERROR_TRANSFER_FAILED");
                            fileDos.flush();
                            // If one file fails, we might want to abort the entire batch.
                            throw new IOException("Transfer failed for file: " + currentReceivedFileName);
                        }
                    } catch (IOException e) { // Catches IOExceptions from the fileDataSocket try-with-resources
                        LogPanel.log("Error during transfer of '" + (currentReceivedFileName != null ? currentReceivedFileName : "unknown file") + "': " + e.getMessage());
                        cb.onError(new Exception("Error during transfer of " + (currentReceivedFileName != null ? currentReceivedFileName : "unknown file") + ": " + e.getMessage(), e));
                        // This error is critical for the current file transfer.
                        // We should break the loop for expectedFileTransfers as the session is likely desynced.
                        break; // Break from the for-loop for expectedFileTransfers
                    }
                } // End of for-loop for expectedFileTransfers
            } catch (IOException e) { // Catches IOExceptions from the handshakeSocket try-with-resources
                LogPanel.log("IOException in handshake/setup or critical file transfer error: " + e.getMessage());
                cb.onError(new Exception("Connection error with Sender or critical transfer failure: " + e.getMessage(), e));
            } finally {
                // This ensures onComplete is called once per accepted handshake session
                cb.onComplete();
                LogPanel.log("File reception session processing finished.");
            }
        } // End of while(true)
        // serverSocket.close(); // This line is unreachable unless while(true) has a break condition
    }

    // Inner class for decompression
    private static class Decompresser { // Made static as it doesn't seem to need FileReceiver instance state

        public boolean decompressFile(String zipFilePath, String destDirectoryPath) { // Renamed for clarity
            File destDir = new File(destDirectoryPath);
            // No need to check destDir.exists() or mkdirs() here if finalSaveBasePath is already created and validated.
            // However, if zip entries create further subdirectories, those need to be handled.

            byte[] buffer = new byte[4096];
            try (FileInputStream fis = new FileInputStream(zipFilePath);
                 BufferedInputStream bis = new BufferedInputStream(fis);
                 ZipInputStream zis = new ZipInputStream(bis)) {

                ZipEntry zipEntry = zis.getNextEntry();
                while (zipEntry != null) {
                    // Sanitize zipEntry.getName() before resolving against destDirectoryPath
                    String entryName = zipEntry.getName();
                    Path entryPath = Paths.get(entryName).normalize(); // Normalize to handle ".." within the entry name itself

                    // Resolve against the destination directory
                    Path newFilePath = Paths.get(destDirectoryPath).resolve(entryPath).normalize();


                    // Security check to prevent Zip Slip vulnerability
                    // Ensure the resolved path is still within the destination directory
                    if (!newFilePath.startsWith(Paths.get(destDirectoryPath).normalize())) {
                        LogPanel.log("Security Alert: Zip entry attempts to write outside target directory (Zip Slip): " + entryName + " -> " + newFilePath);
                        return false;
                    }

                    File newFile = newFilePath.toFile();

                    if (zipEntry.isDirectory()) {
                        if (!newFile.exists() && !newFile.mkdirs()) {
                            LogPanel.log("Error: Failed to create directory from zip entry: " + newFile.getAbsolutePath());
                            // Consider if this should be a fatal error for decompression
                        }
                    } else {
                        File parent = newFile.getParentFile();
                        if (parent != null && !parent.exists() && !parent.mkdirs()) {
                            LogPanel.log("Error: Failed to create parent directory for zip entry file: " + parent.getAbsolutePath());
                            return false; // If parent dir cannot be created, file cannot be written
                        }

                        try (FileOutputStream fos = new FileOutputStream(newFile);
                             BufferedOutputStream bos = new BufferedOutputStream(fos)) {
                            int len;
                            while ((len = zis.read(buffer)) > 0) {
                                bos.write(buffer, 0, len);
                            }
                        }
                    }
                    zis.closeEntry();
                    zipEntry = zis.getNextEntry();
                }
                LogPanel.log("Successfully decompressed " + zipFilePath + " to " + destDirectoryPath);
                return true;
            } catch (IOException e) {
                LogPanel.log("Error decompressing file " + zipFilePath + ": " + e.getMessage());
                // e.printStackTrace(); // LogPanel should be sufficient
                return false;
            } catch (InvalidPathException e) {
                LogPanel.log("Error: Invalid path in zip entry name: " + e.getMessage());
                return false;
            }
        }
    }
}
