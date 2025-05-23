package AirShit;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import AirShit.ui.LogPanel;

public class FileReceiver {

    public int port;
    private final int ITHREADS = Runtime.getRuntime().availableProcessors();
    // These are now set per handshake
    // public String currentSenderName;
    // public Long currentTotalSize;

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 30; // Increased for multi-stage handshake

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
        public String toString() { return name + " (" + SendFileGUI.formatFileSize(size) + ")"; }
    }


    FileReceiver(int port) {
        this.port = port;
    }

    public void start(TransferCallback callback) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            LogPanel.log("FileReceiver started on port: " + port + ". Waiting for senders...");

            while (true) {
                Socket handshakeSocket = null;
                List<FileInfo> filesExpected = new ArrayList<>();
                long totalSizeFromSender = 0;
                String senderNameFromSender = null;
                int clientAnnouncedThreads = 1;
                int numFilesToExpect = 0; // Declare numFilesToExpect here
                File selectedSaveDirectory = null; // This will be the parent directory chosen by user
                String originalFolderNameFromSender = null; // To store the original folder name if it's a directory transfer
                boolean isDirectoryTransferFromSender = false;
                boolean proceedWithTransfer = false;
                int negotiatedThreadCount = 1;


                try {
                    LogPanel.log("FileReceiver: Waiting for a new sender to connect for handshake...");
                    handshakeSocket = serverSocket.accept();
                    LogPanel.log("FileReceiver: Accepted handshake connection from " + handshakeSocket.getRemoteSocketAddress());
                    handshakeSocket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000);

                    try (DataInputStream dis = new DataInputStream(handshakeSocket.getInputStream());
                         DataOutputStream dos = new DataOutputStream(handshakeSocket.getOutputStream())) {

                        // Phase 1: Read Initial Metadata
                        String initialMetadata = null; // Initialize
                        try {
                            initialMetadata = dis.readUTF();
                            LogPanel.log("FileReceiver: Received initial metadata: " + initialMetadata);
                            String[] metaParts = initialMetadata.split("@");
                            if (metaParts.length < 6) { 
                                throw new IOException("Invalid initial metadata format (expected 6 parts, got " + metaParts.length + "): " + initialMetadata);
                            }
                            senderNameFromSender = metaParts[0];
                            numFilesToExpect = Integer.parseInt(metaParts[1]); // Now assigns to the declared variable
                            totalSizeFromSender = Long.parseLong(metaParts[2]); // Potential NumberFormatException
                            clientAnnouncedThreads = Integer.parseInt(metaParts[3]); // Potential NumberFormatException
                            isDirectoryTransferFromSender = "1".equals(metaParts[4]);
                            originalFolderNameFromSender = metaParts[5];

                            LogPanel.log(String.format("FileReceiver: Parsed Metadata: Sender=%s, NumFiles=%d, TotalSize=%s, ClientThreads=%d, IsDir=%b, OrigFolder=%s",
                                    senderNameFromSender, numFilesToExpect, SendFileGUI.formatFileSize(totalSizeFromSender), clientAnnouncedThreads, isDirectoryTransferFromSender, originalFolderNameFromSender));
                            
                            dos.writeUTF("ACK_METADATA");
                            dos.flush();
                            LogPanel.log("FileReceiver: Sent ACK_METADATA.");

                        } catch (NumberFormatException e) {
                            LogPanel.log("FileReceiver: Error parsing metadata numbers: " + e.getMessage() + " from metadata: " + initialMetadata);
                            // Consider sending a NACK or just closing the socket, which would lead to EOF or other error on sender side
                            // For now, let the exception propagate to the outer catch, which will close the socket.
                            throw new IOException("Metadata parsing error (numbers): " + e.getMessage(), e);
                        } catch (ArrayIndexOutOfBoundsException e) {
                            LogPanel.log("FileReceiver: Error parsing metadata (not enough parts): " + e.getMessage() + " from metadata: " + initialMetadata);
                            throw new IOException("Metadata parsing error (parts): " + e.getMessage(), e);
                        } catch (Exception e) { // Catch any other unexpected error during this critical phase
                            LogPanel.log("FileReceiver: Unexpected error during initial metadata processing or ACK sending: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                            // e.printStackTrace(); // For more detailed debugging if needed
                            throw e; // Re-throw to ensure the handshake socket is closed by the outer try-finally
                        }
                        boolean hasLz4 = false;
                        // Phase 2: Read File Info Loop
                        for (int i = 0; i < numFilesToExpect; i++) { // Now numFilesToExpect is resolved
                            String fileInfoString = dis.readUTF();
                            LogPanel.log("FileReceiver: Received file info (" + (i + 1) + "/" + numFilesToExpect + "): " + fileInfoString);
                            String[] fileInfoParts = fileInfoString.split("@");
                            if (fileInfoParts.length < 2) {
                                throw new IOException("Invalid file info format: " + fileInfoString);
                            }
                            if(fileInfoParts[0].endsWith(".tar.lz4")) {
                                hasLz4 = true;
                            }
                            filesExpected.add(new FileInfo(fileInfoParts[0], Long.parseLong(fileInfoParts[1])));
                            dos.writeUTF("ACK_FILE_INFO");
                            dos.flush();
                        }
                        LogPanel.log("FileReceiver: Received all file infos. Total files: " + filesExpected.size());

                        // Phase 3: User Interaction and Decision
                        StringBuilder fileListForDialog = new StringBuilder();
                        if (isDirectoryTransferFromSender && originalFolderNameFromSender != null && !originalFolderNameFromSender.equals("-")) {
                            fileListForDialog.append("Folder: ").append(originalFolderNameFromSender).append("\nContaining:\n");
                        }
                        for(FileInfo fi : filesExpected) {
                            fileListForDialog.append("  ").append(fi.name).append(" (").append(SendFileGUI.formatFileSize(fi.size)).append(")\n");
                        }

                        FileReceiveDialog dialog = new FileReceiveDialog(Main.GUI, fileListForDialog, senderNameFromSender, SendFileGUI.formatFileSize(totalSizeFromSender));
                        boolean userAccepted = dialog.showDialog();

                        if (userAccepted) {
                            JFileChooser saveLocationChooser = new JFileChooser();
                            saveLocationChooser.setDialogTitle("Select Save Location for files from " + senderNameFromSender);
                            saveLocationChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                            saveLocationChooser.setAcceptAllFileFilterUsed(false);
                            File defaultSaveDir = new File(System.getProperty("user.home") + File.separator + "Downloads");
                            if (!defaultSaveDir.exists()) defaultSaveDir.mkdirs();
                            saveLocationChooser.setCurrentDirectory(defaultSaveDir);
                            int chooserResult = saveLocationChooser.showDialog(Main.GUI, "Select Folder");
                            if (chooserResult == JFileChooser.APPROVE_OPTION) {
                                selectedSaveDirectory = saveLocationChooser.getSelectedFile(); // This is the base directory chosen by user
                                LogPanel.log("User selected base save directory: " + selectedSaveDirectory.getAbsolutePath());

                                if (isDirectoryTransferFromSender && originalFolderNameFromSender != null && !originalFolderNameFromSender.equals("-") && hasLz4) {
                                    File targetFolder = new File(selectedSaveDirectory, originalFolderNameFromSender);
                                    if (!targetFolder.exists()) {
                                        if (targetFolder.mkdirs()) {
                                            LogPanel.log("Created target sub-directory: " + targetFolder.getAbsolutePath());
                                            selectedSaveDirectory = targetFolder; // Update selectedSaveDirectory to be the new sub-folder
                                        } else {
                                            LogPanel.log("Error: Failed to create target sub-directory: " + targetFolder.getAbsolutePath() + ". Files will be saved in the parent directory.");
                                            // Optionally, set userAccepted = false or show an error to the user
                                            // For now, we'll proceed to save in the parent if sub-folder creation fails.
                                        }
                                    } else {
                                        LogPanel.log("Target sub-directory already exists: " + targetFolder.getAbsolutePath());
                                        selectedSaveDirectory = targetFolder; // Update selectedSaveDirectory to use the existing sub-folder
                                    }
                                }
                            } else {
                                LogPanel.log("User cancelled save location selection.");
                                userAccepted = false;
                            }
                        }

                        String decisionMessage;
                        if (userAccepted && selectedSaveDirectory != null && selectedSaveDirectory.isDirectory()) {
                            negotiatedThreadCount = Math.min(clientAnnouncedThreads, ITHREADS);
                            negotiatedThreadCount = Math.max(1, negotiatedThreadCount);
                            decisionMessage = "OK@" + negotiatedThreadCount;
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
                            LogPanel.log("FileReceiver: Waiting for sender's ACK to our '" + decisionMessage + "' message...");
                            String senderAckToDecision = dis.readUTF();
                            if (!"ACK_DECISION".equals(senderAckToDecision)) {
                                LogPanel.log("Error: Sender did not ACK our OK@ message. Received: '" + senderAckToDecision + "'. Aborting this transfer.");
                                proceedWithTransfer = false; // Critical: ensure we don't proceed
                            } else {
                                LogPanel.log("FileReceiver: Sender ACKed our OK@ message. Handshake fully complete. Preparing for data sockets.");
                            }
                        }
                        System.out.println("negotiatedThreadCount: " + negotiatedThreadCount );
                        // Data Reception Loop (if proceedWithTransfer is true)
                        if (proceedWithTransfer) {
                            LogPanel.log("FileReceiver: Initializing Receiver module for data transfer...");
                            if (callback != null) callback.onStart(totalSizeFromSender);

                            boolean overallSuccess = true;
                            for (FileInfo currentFileToReceive : filesExpected) {
                                String outputFileName = currentFileToReceive.name;
                                long fileSizeForThisFile = currentFileToReceive.size;
                                // selectedSaveDirectory now correctly points to the base chosen by user OR the newly created/existing sub-folder
                                String wholeOutputFilePath = selectedSaveDirectory.getAbsolutePath() + File.separator + outputFileName;

                                LogPanel.log("FileReceiver: Starting data reception for " + outputFileName + " -> " + wholeOutputFilePath + " (" + SendFileGUI.formatFileSize(fileSizeForThisFile) + ")");

                                // Receiver class is responsible for handling the data transfer for ONE file.
                                // The serverSocket argument to Receiver constructor is not used by its start method if it connects out.
                                // This might need review based on Receiver.java's actual implementation.
                                Receiver dataReceiver = new Receiver(serverSocket); 
                                boolean receptionWasSuccessful = false;
                                try {
                                    receptionWasSuccessful = dataReceiver.start(wholeOutputFilePath, fileSizeForThisFile, negotiatedThreadCount, callback);
                                    if (receptionWasSuccessful) {
                                        LogPanel.log("FileReceiver: Data reception successful for: " + wholeOutputFilePath);
                                        if (outputFileName.endsWith(".tar.lz4")) {
                                            // Decompress into the same directory where the .tar.lz4 was saved
                                            String decompressedTargetFolder = selectedSaveDirectory.getAbsolutePath();
                                            LogPanel.log("FileReceiver: Decompressing " + wholeOutputFilePath + " into folder " + decompressedTargetFolder);
                                            try {
                                                LZ4FileDecompressor.decompressTarLz4Folder(wholeOutputFilePath, decompressedTargetFolder);
                                                LogPanel.log("FileReceiver: Decompression complete into " + decompressedTargetFolder);
                                                // Delete the .tar.lz4 file after successful decompression
                                                try {
                                                    Files.deleteIfExists(Paths.get(wholeOutputFilePath));
                                                    LogPanel.log("FileReceiver: Deleted archive " + wholeOutputFilePath + " after decompression.");
                                                } catch (IOException eDel) {
                                                    LogPanel.log("FileReceiver: Error deleting archive " + wholeOutputFilePath + " after decompression: " + eDel.getMessage());
                                                }
                                            } catch (Exception eDecompress) {
                                                LogPanel.log("FileReceiver: Error decompressing " + wholeOutputFilePath + ": " + eDecompress.getMessage());
                                                if (callback != null) callback.onError(new IOException("Decompression failed for " + outputFileName, eDecompress));
                                                overallSuccess = false; // Mark overall transfer as failed if decompression fails
                                                // break; // Optionally break if decompression failure is critical for subsequent files
                                            }
                                        }
                                    } else {
                                        LogPanel.log("FileReceiver: Data reception process reported failure for " + outputFileName);
                                        if (callback != null) callback.onError(new IOException("Reception failed for " + outputFileName));
                                        overallSuccess = false;
                                        break; // Stop processing further files if one fails to receive
                                    }
                                } catch (InterruptedException e_intr) {
                                    Thread.currentThread().interrupt();
                                    LogPanel.log("FileReceiver: Data reception interrupted for " + outputFileName + ": " + e_intr.getMessage());
                                    if (callback != null) callback.onError(e_intr);
                                    overallSuccess = false;
                                    break; 
                                } catch (Exception e_recv) { // Catch generic Exception from dataReceiver.start()
                                    LogPanel.log("FileReceiver: Error during data reception for " + outputFileName + ": " + e_recv.getClass().getName() + " - " + e_recv.getMessage());
                                    if (callback != null) callback.onError(e_recv);
                                    overallSuccess = false;
                                    break;
                                }
                            } // End of loop for filesExpected


                            if (overallSuccess && callback != null) {
                                callback.onComplete();
                            } else if (!overallSuccess && callback != null) {
                                // onError would have been called by the failing part.
                                // No explicit onError here unless to signal a general "multi-file transfer incomplete".
                                LogPanel.log("FileReceiver: Overall multi-file transfer did not complete successfully.");
                            }

                        } else { // proceedWithTransfer was false
                            LogPanel.log("FileReceiver: Handshake failed or transfer rejected. Not proceeding to data reception for this attempt.");
                            if (callback != null && totalSizeFromSender > 0) {
                                callback.onError(new IOException("Transfer rejected or handshake failed."));
                            } else if (callback != null) { // No specific error, but not proceeding
                                callback.onError(new IOException("Transfer not initiated."));
                            }
                        }
                    } // Streams dis/dos are closed here.
                } catch (SocketTimeoutException e) {
                    LogPanel.log("FileReceiver: Timeout during handshake phase with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getMessage());
                    if (callback != null) callback.onError(e);
                } catch (EOFException e) {
                    LogPanel.log("FileReceiver: EOF during handshake with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ". Client likely disconnected. " + e.getMessage());
                    if (callback != null) callback.onError(e);
                } catch (IOException e) {
                    LogPanel.log("FileReceiver: IOException during handshake phase with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getMessage());
                    if (callback != null) callback.onError(e);
                } catch (Exception e) { // Catch-all for other handshake processing errors
                    LogPanel.log("FileReceiver: General error during handshake processing with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    if (callback != null) callback.onError(e);
                } finally {
                    if (handshakeSocket != null && !handshakeSocket.isClosed()) {
                        try {
                            handshakeSocket.close();
                            LogPanel.log("FileReceiver: Handshake socket with " + (handshakeSocket.getRemoteSocketAddress() != null ? handshakeSocket.getRemoteSocketAddress() : "previous client") + " closed.");
                        } catch (IOException ex) {
                            LogPanel.log("FileReceiver: Error closing handshake socket: " + ex.getMessage());
                        }
                    }
                }
                LogPanel.log("FileReceiver: Finished handling current sender. Ready for next handshake.");
            } // End while(true)
        } // ServerSocket closed here
    }

    // FileReceiveDialog class (ensure Main.GUI and SendFileGUI.formatFileSize are accessible and correct)
    // ... (FileReceiveDialog class remains largely the same, ensure it can display multiple file names from the StringBuilder)
    private class FileReceiveDialog extends JDialog {
        private boolean accepted = false;
        // Constructor now takes StringBuilder for more flexible content
        public FileReceiveDialog(JFrame owner, StringBuilder fileListContent, String senderName, String totalSizeStr) {
            super(owner, "Incoming Transfer from " + senderName, true); // Title updated
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            // setSize(450, 350); // Adjust size as needed
            setMinimumSize(new Dimension(450,300));
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
                System.err.println("Could not find resource: /asset/data-transfer.png. Place it in src/main/resources/asset/");
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