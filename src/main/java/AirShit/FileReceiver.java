package AirShit;

import java.awt.*;
import java.io.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import AirShit.ui.LogPanel;
import AirShit.ui.FXFileChooserAdapter;

public class FileReceiver {
    private String SPLIT_CHAR = "\\\\"; // 用於分隔元數據的字元
    public int port;
    private int port2;
    private final int ITHREADS = Runtime.getRuntime().availableProcessors() * 4;
    // private final int ITHREADS = 1<<30;
    private File selectedSaveDirectory; // 確保這是 FileReceiver 的成員變數
    // These are now set per handshake
    // public String currentSenderName;
    // public Long currentTotalSize;

    // Simple POJO to store received file information
    private static class FileInfo {
        String name;
        long size;
        // String localPath; // Can be added if needed later

        FileInfo(String name, long size) {
            this.name = name;
            this.size = size;
        }

        @Override
        public String toString() {
            return name + " (" + SendFileGUI.formatFileSize(size) + ")";
        }
    }

    FileReceiver(int port , int port2) {
        this.port = port;
        this.port2 = port2;
    }

    public void start(TransferCallback callback) throws IOException {
        while (true) {
            try (ServerSocketChannel serverSocketChannel = ServerSocketChannel.open()) {
                serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true); // <— 新增
                serverSocketChannel.bind(new InetSocketAddress(port));
                serverSocketChannel.configureBlocking(true);
                LogPanel.log("FileReceiver: Listening on port " + port + " for handshake...");

                SocketChannel handshakeChannel = null;
                List<FileInfo> filesExpected = new ArrayList<>();
                long totalSizeFromSender = 0;
                String senderNameFromSender = null;
                int clientAnnouncedThreads = 1;
                int numFilesToExpect = 0; // Declare numFilesToExpect here
                String originalFolderNameFromSender = null; // To store the original folder name if it's a directory
                                                            // transfer
                boolean isDirectoryTransferFromSender = false;
                boolean proceedWithTransfer = false;
                int negotiatedThreadCount = 1;

                try {
                    handshakeChannel = serverSocketChannel.accept();
                    LogPanel.log("FileReceiver: Accepted handshake from "
                            + handshakeChannel.getRemoteAddress());
                    // Setup handshake streams
                    handshakeChannel.configureBlocking(true);
                    try (DataInputStream dis = new DataInputStream(Channels.newInputStream(handshakeChannel));
                            DataOutputStream dos = new DataOutputStream(Channels.newOutputStream(handshakeChannel))) {

                        // Phase 1: Read Initial Metadata
                        String initialMetadata = null; // Initialize
                        try {
                            initialMetadata = dis.readUTF();
                            LogPanel.log("FileReceiver: Received initial metadata: " + initialMetadata);
                            System.out.println("metaParts: " + initialMetadata);
                            String[] metaParts = initialMetadata.split(Pattern.quote(SPLIT_CHAR));
                            // Expecting 6 parts: senderName, numFiles, totalSize, requestedThreads, isDir,
                            // origFolder
                            if (metaParts.length < 6) {
                                throw new IOException("Invalid initial metadata format (expected 6 parts, got "
                                        + metaParts.length + "): " + initialMetadata);
                            }
                            senderNameFromSender = metaParts[0];
                            numFilesToExpect = Integer.parseInt(metaParts[1]); // Now assigns to the declared variable
                            totalSizeFromSender = Long.parseLong(metaParts[2]); // Potential NumberFormatException
                            clientAnnouncedThreads = Integer.parseInt(metaParts[3]); // Potential NumberFormatException
                            isDirectoryTransferFromSender = "1".equals(metaParts[4]);
                            // Guard original folder name if provided
                            originalFolderNameFromSender = metaParts[5] != null ? metaParts[5] : "-";

                            LogPanel.log(String.format(
                                    "FileReceiver: Parsed Metadata: Sender=%s, NumFiles=%d, TotalSize=%s, ClientThreads=%d, IsDir=%b, OrigFolder=%s",
                                    senderNameFromSender, numFilesToExpect,
                                    SendFileGUI.formatFileSize(totalSizeFromSender), clientAnnouncedThreads,
                                    isDirectoryTransferFromSender, originalFolderNameFromSender));

                            dos.writeUTF("ACK_METADATA");
                            dos.flush();
                            // LogPanel.log("FileReceiver: Sent ACK_METADATA.");

                        } catch (NumberFormatException e) {
                            // LogPanel.log("FileReceiver: Error parsing metadata numbers: " +
                            // e.getMessage()
                            // + " from metadata: " + initialMetadata);
                            // Consider sending a NACK or just closing the socket, which would lead to EOF
                            // or other error on sender side
                            // For now, let the exception propagate to the outer catch, which will close the
                            // socket.
                            throw new IOException("Metadata parsing error (numbers): " + e.getMessage(), e);
                        } catch (ArrayIndexOutOfBoundsException e) {
                            // LogPanel.log("FileReceiver: Error parsing metadata (not enough parts): " +
                            // e.getMessage()
                            // + " from metadata: " + initialMetadata);
                            throw new IOException("Metadata parsing error (parts): " + e.getMessage(), e);
                        } catch (Exception e) { // Catch any other unexpected error during this critical phase
                            // LogPanel.log(
                            // "FileReceiver: Unexpected error during initial metadata processing or ACK
                            // sending: "
                            // + e.getClass().getSimpleName() + " - " + e.getMessage());
                            // e.printStackTrace(); // For more detailed debugging if needed
                            throw e; // Re-throw to ensure the handshake socket is closed by the outer try-finally
                        }
                        // Phase 2: Read File Info Loop
                        // LogPanel.log("FileReceiver: Received file info (" + (i + 1) + "/" +
                        // numFilesToExpect + "): "
                        // + fileInfoString);
                        for (int i = 0; i < numFilesToExpect; i++) { // Now numFilesToExpect is resolved
                            String fileInfoString = dis.readUTF();
                            String[] fileInfoParts = fileInfoString.split(Pattern.quote(SPLIT_CHAR));
                            if (fileInfoParts.length < 2) {
                                throw new IOException("Invalid file info format: " + fileInfoString);
                            }
                            filesExpected.add(new FileInfo(fileInfoParts[0], Long.parseLong(fileInfoParts[1])));
                            dos.writeUTF("ACK_FILE_INFO");
                            dos.flush();
                        }
                        LogPanel.log("FileReceiver: Received all file infos. Total files: " + filesExpected.size());

                        // Phase 3: User Interaction and Decision
                        StringBuilder fileListForDialog = new StringBuilder();
                        if (isDirectoryTransferFromSender && originalFolderNameFromSender != null
                                && !originalFolderNameFromSender.equals("-")) {
                            fileListForDialog.append("Folder: ").append(originalFolderNameFromSender)
                                    .append("\nContaining:\n");
                        }
                        for (FileInfo fi : filesExpected) {
                            fileListForDialog.append("  ").append(fi.name).append(" (")
                                    .append(SendFileGUI.formatFileSize(fi.size)).append(")\n");
                        }

                        FileReceiveDialog dialog = new FileReceiveDialog(Main.GUI, fileListForDialog,
                                senderNameFromSender, SendFileGUI.formatFileSize(totalSizeFromSender));
                        boolean userAccepted = dialog.showDialog();

                        if (userAccepted) {
                            if (selectedSaveDirectory != null && selectedSaveDirectory.isDirectory()) {
                                LogPanel.log("User selected base save directory: "
                                        + selectedSaveDirectory.getAbsolutePath());
                            } else {
                                LogPanel.log("User cancelled save location selection or invalid directory.");
                                userAccepted = false;
                            }
                        }

                        String decisionMessage;
                        if (userAccepted && selectedSaveDirectory != null && selectedSaveDirectory.isDirectory()) {
                            negotiatedThreadCount = Math.min(clientAnnouncedThreads, ITHREADS);
                            negotiatedThreadCount = Math.max(1, negotiatedThreadCount);
                            decisionMessage = "OK" + SPLIT_CHAR + negotiatedThreadCount;
                            proceedWithTransfer = true;
                            LogPanel.log("FileReceiver: User accepted. Sending " + decisionMessage);
                        } else {
                            decisionMessage = "REJECT";
                            LogPanel.log("FileReceiver: User rejected or invalid save path. Sending REJECT.");
                            // proceedWithTransfer remains false
                        }
                        dos.writeUTF(decisionMessage);
                        dos.flush();

                        // Phase 4: Wait for Sender's ACK to our decision (if we sent OK)
                        if (proceedWithTransfer) {
                            LogPanel.log("FileReceiver: Waiting for sender's ACK to our '" + decisionMessage
                                    + "' message...");
                            String senderAckToDecision = dis.readUTF();
                            if (!"ACK_DECISION".equals(senderAckToDecision)) {
                                LogPanel.log("Error: Sender did not ACK our OK@ message. Received: '"
                                        + senderAckToDecision + "'. Aborting this transfer.");
                                proceedWithTransfer = false; // Critical: ensure we don't proceed
                            } else {
                                LogPanel.log(
                                        "FileReceiver: Sender ACKed our OK@ message. Handshake fully complete. Preparing for data sockets.");
                            }
                        }
                        System.out.println("negotiatedThreadCount: " + negotiatedThreadCount);
                        // Data Reception Loop (if proceedWithTransfer is true)
                        if (proceedWithTransfer) {
                            LogPanel.log("FileReceiver: Initializing Receiver module for data transfer...");
                            // Call onFileStart callback
                            int totalFiles = filesExpected.size();
                            boolean overallSuccess = true;
                            int currentFileIndex = 0;
                            for (FileInfo currentFileToReceive : filesExpected) {
                                String outputFileName = currentFileToReceive.name;
                                long fileSizeForThisFile = currentFileToReceive.size;
                                // selectedSaveDirectory now correctly points to the base chosen by user OR the
                                // newly created/existing sub-folder
                                String wholeOutputFilePath = selectedSaveDirectory.getAbsolutePath() + File.separator
                                        + outputFileName;
                                System.out.println(
                                        "wholeOutputFilePath: " + wholeOutputFilePath + " fileSizeForThisFile: "
                                                + fileSizeForThisFile);
                                File outputFile = new File(wholeOutputFilePath);
                                if (outputFile.exists() == false) {
                                    outputFile.getParentFile().mkdirs(); // Ensure parent directories exist
                                    outputFile.createNewFile(); // Create the file if it doesn't exist
                                    LogPanel.log("FileReceiver: Created new file: " + wholeOutputFilePath);
                                }
                                if (callback != null) {
                                    callback.onFileStart(currentFileIndex++, totalFiles, outputFileName);
                                    callback.onStart(fileSizeForThisFile, outputFileName);
                                }
                                // receive START_FILE message

                                // now open a listener on dataPort, not `port`:

                                String startFileMessage = dis.readUTF();
                                if (!"START_FILE".equals(startFileMessage)) {
                                    throw new IOException("Expected START_FILE but got: " + startFileMessage);
                                }
                                dos.writeUTF("START_FILE_ACK");
                                dos.flush();
                                // Calculate expected number of chunks to receive and use constructor to limit
                                // loop
                                int expectedChunks = (int) ((fileSizeForThisFile + ReceiverOptimized.DEFAULT_CHUNK_SIZE
                                        - 1)
                                        / ReceiverOptimized.DEFAULT_CHUNK_SIZE);
                                ReceiverOptimized dataReceiver = new ReceiverOptimized(
                                        port2,
                                        wholeOutputFilePath,
                                        negotiatedThreadCount,
                                        callback,
                                        expectedChunks);
                                // new Thread(() -> {
                                try {
                                    dataReceiver.start();
                                } catch (Exception e) {
                                }
                                if (new File(wholeOutputFilePath).exists() && wholeOutputFilePath.endsWith(".tar")) {
                                    try {
                                        String decompressedTargetFolder = selectedSaveDirectory.getAbsolutePath();
                                        TarExtractor.start(new File(wholeOutputFilePath),
                                                new File(decompressedTargetFolder));
                                        // LogPanel.log("FileReceiver: Decompression complete into "
                                        // + decompressedTargetFolder);
                                        // Delete the .tar file after successful decompression
                                        try {
                                            Files.deleteIfExists(Paths.get(wholeOutputFilePath));
                                            // LogPanel.log("FileReceiver: Deleted archive " + wholeOutputFilePath
                                            // + " after decompression.");
                                        } catch (IOException eDel) {
                                            // LogPanel.log("FileReceiver: Error deleting archive "
                                            // + wholeOutputFilePath + " after decompression: "
                                            // + eDel.getMessage());
                                        }
                                    } catch (Exception eDecompress) {

                                    }
                                }
                                // }).run();
                                if (callback != null) {
                                    callback.onFileComplete(currentFileIndex - 1, totalFiles, outputFileName);
                                    callback.onComplete(outputFileName);
                                }

                            } // End of loop for filesExpected

                            if (callback != null) {
                                callback.onComplete();
                            }
                        }
                    } // Streams dis/dos are closed here.
                } catch (SocketTimeoutException e) {
                    // LogPanel.log("FileReceiver: Timeout during handshake phase with "
                    // + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() :
                    // "unknown client")
                    // + ": " + e.getMessage());
                    // if (callback != null)
                    // callback.onError(e);
                } catch (EOFException e) {
                    // LogPanel.log("FileReceiver: EOF during handshake with "
                    // + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() :
                    // "unknown client")
                    // + ". Client likely disconnected. " + e.getMessage());
                    // if (callback != null)
                    // callback.onError(e);
                } catch (IOException e) {
                    // LogPanel.log("FileReceiver: IOException during handshake phase with "
                    // + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() :
                    // "unknown client")
                    // + ": " + e.getMessage());
                    // if (callback != null)
                    // callback.onError(e);
                } catch (Exception e) { // Catch-all for other handshake processing errors
                    // LogPanel.log("FileReceiver: General error during handshake processing with "
                    // + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() :
                    // "unknown client")
                    // + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    // if (callback != null)
                    // callback.onError(e);
                } finally {
                    if (handshakeChannel != null && handshakeChannel.isOpen()) {
                        try {
                            handshakeChannel.close();
                            LogPanel.log("FileReceiver: Closed handshake socket to prevent reuse as data channel.");
                        } catch (IOException ex) {
                            // LogPanel.log("FileReceiver: Error closing handshake socket: " +
                            // ex.getMessage());
                        }
                    }
                }
                LogPanel.log("FileReceiver: Finished handling current sender. Ready for next handshake.");
            } // End while(true)
        } // ServerSocket closed here
    }

    // FileReceiveDialog class (ensure Main.GUI and SendFileGUI.formatFileSize are
    // accessible and correct)
    // ... (FileReceiveDialog class remains largely the same, ensure it can display
    // multiple file names from the StringBuilder)
    private class FileReceiveDialog extends JDialog {
        private boolean accepted = false;

        // Constructor now takes StringBuilder for more flexible content
        public FileReceiveDialog(JFrame owner, StringBuilder fileListContent, String senderName, String totalSizeStr) {
            super(owner, "Incoming Transfer from " + senderName, true); // Title updated
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            // setSize(450, 350); // Adjust size as needed
            setMinimumSize(new Dimension(450, 300));
            setLocationRelativeTo(owner);

            JPanel content = new JPanel(new BorderLayout(10, 10));
            content.setBorder(new EmptyBorder(15, 15, 15, 15));
            setContentPane(content);

            JPanel topPanel = new JPanel(new BorderLayout(8, 8));
            JLabel iconLabel = new JLabel();
            java.net.URL iconUrl = this.getClass().getResource("/asset/data-transfer.png");
            if (iconUrl != null) {
                ImageIcon dataIcon = new ImageIcon(iconUrl);
                if (dataIcon.getImageLoadStatus() == MediaTracker.COMPLETE) {
                    Image image = dataIcon.getImage();
                    int targetSize = 30;
                    Image scaled = image.getScaledInstance(targetSize, targetSize, Image.SCALE_SMOOTH);
                    iconLabel.setIcon(new ImageIcon(scaled));
                } else {
                    System.err.println("Failed to load image: /asset/data-transfer.png");
                    iconLabel.setText("[X]");
                }
            } else {
                System.err.println(
                        "Could not find resource: /asset/data-transfer.png. Place it in src/main/resources/asset/");
                iconLabel.setText("[?]");
            }
            topPanel.add(iconLabel, BorderLayout.WEST);

            JPanel infoPanel = new JPanel(new GridLayout(2, 1));
            infoPanel.add(new JLabel("Sender: " + senderName));
            infoPanel.add(new JLabel("Total Size: " + totalSizeStr));
            topPanel.add(infoPanel, BorderLayout.CENTER);
            content.add(topPanel, BorderLayout.NORTH);

            JTextArea fileListArea = new JTextArea(fileListContent.toString());
            fileListArea.setEditable(false);
            fileListArea.setLineWrap(true);
            fileListArea.setWrapStyleWord(true);
            JScrollPane scroll = new JScrollPane(fileListArea);
            scroll.setBorder(BorderFactory.createTitledBorder("Files to Receive"));
            content.add(scroll, BorderLayout.CENTER);

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton acceptBtn = new JButton("Accept");
            JButton rejectBtn = new JButton("Reject");

            acceptBtn.addActionListener(e -> {
                accepted = true;

                // 使用 FXFileChooserAdapter 來選擇保存目錄
                File selectedDirectory = FXFileChooserAdapter.showFileChooser();
                if (selectedDirectory != null && selectedDirectory.isDirectory()) {
                    selectedSaveDirectory = selectedDirectory; // 更新 FileReceiver 的成員變數
                    LogPanel.log("User selected save directory: " + selectedSaveDirectory.getAbsolutePath());
                } else {
                    LogPanel.log("User cancelled save directory selection.");
                    accepted = false; // 如果未選擇有效目錄，則視為拒絕
                }

                dispose();
            });
            rejectBtn.addActionListener(e -> {
                accepted = false;
                dispose();
            });

            buttonPanel.add(rejectBtn);
            buttonPanel.add(acceptBtn);
            content.add(buttonPanel, BorderLayout.SOUTH);
            pack(); // Pack the dialog to fit its contents
        }

        public boolean showDialog() {
            setVisible(true);
            return accepted;
        }
    }
}