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
import AirShit.ui.LogPanel;

public class FileReceiver {

    public int port;
    private final int ITHREADS = Runtime.getRuntime().availableProcessors();
    public String currentSenderName;
    public Long currentTotalSize;

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 15; // Increased slightly

    FileReceiver(int port) {
        this.port = port;
    }

    public void start(TransferCallback callback) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            LogPanel.log("FileReceiver started on port: " + port + ". Waiting for senders...");

            while (true) {
                Socket handshakeSocket = null;
                String outPutFileName = null; // The name of the file as received from sender (e.g., archive.tar.lz4)
                String wholeOutputFilePath = null; // Full path to save the received file
                String decompressedOutputFolderPath = null; // Path for decompressed content if applicable

                int negotiatedThreadCount = 1;

                currentSenderName = null;
                currentTotalSize = null;

                try {
                    LogPanel.log("FileReceiver: Waiting for a new sender to connect for handshake...");
                    handshakeSocket = serverSocket.accept();
                    LogPanel.log("FileReceiver: Accepted handshake connection from " + handshakeSocket.getRemoteSocketAddress());
                    handshakeSocket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000);

                    try (DataInputStream dis = new DataInputStream(handshakeSocket.getInputStream());
                         DataOutputStream dos = new DataOutputStream(handshakeSocket.getOutputStream())) {

                        String handShakeMsg = dis.readUTF();
                        LogPanel.log("FileReceiver: Received initial handshake message: " + handShakeMsg);

                        if (handShakeMsg.contains("@")) {
                            String[] parts = handShakeMsg.split("@");
                            if (parts.length < 4) {
                                LogPanel.log("Error: Invalid handshake format (not enough parts): " + handShakeMsg);
                                dos.writeUTF("ERROR_HANDSHAKE_FORMAT");
                                dos.flush();
                                continue;
                            }

                            currentSenderName = parts[0];
                            String clientAnnouncedFileNameForDialog = parts[1]; // Used for dialog display
                            int clientAnnouncedThreads = Integer.parseInt(parts[parts.length - 2]);
                            currentTotalSize = Long.parseLong(parts[parts.length - 1]);

                            LogPanel.log(String.format("FileReceiver: Parsed Handshake: Sender=%s, FileForDialog=%s, Threads=%d, Size=%d",
                                    currentSenderName, clientAnnouncedFileNameForDialog, clientAnnouncedThreads, currentTotalSize));

                            dos.writeUTF("ACK");
                            dos.flush();
                            LogPanel.log("FileReceiver: Sent ACK for initial handshake.");

                            FileReceiveDialog dialog = new FileReceiveDialog(Main.GUI, new StringBuilder(clientAnnouncedFileNameForDialog), currentSenderName, SendFileGUI.formatFileSize(currentTotalSize));
                            boolean userAccepted = dialog.showDialog();
                            File selectedSaveDirectory = null;

                            if (userAccepted) {
                                JFileChooser saveLocationChooser = new JFileChooser();
                                saveLocationChooser.setDialogTitle("Select Save Location for files from " + currentSenderName);
                                saveLocationChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                                saveLocationChooser.setAcceptAllFileFilterUsed(false);
                                File defaultSaveDir = new File(System.getProperty("user.home") + File.separator + "Downloads");
                                if (!defaultSaveDir.exists()) defaultSaveDir.mkdirs();
                                saveLocationChooser.setCurrentDirectory(defaultSaveDir);
                                int chooserResult = saveLocationChooser.showDialog(Main.GUI, "Select Folder");
                                if (chooserResult == JFileChooser.APPROVE_OPTION) {
                                    selectedSaveDirectory = saveLocationChooser.getSelectedFile();
                                    LogPanel.log("User selected save directory: " + selectedSaveDirectory.getAbsolutePath());
                                } else {
                                    LogPanel.log("User cancelled save location selection.");
                                    userAccepted = false;
                                }
                            }

                            String decisionMessage;
                            boolean proceedWithTransfer = false;

                            if (userAccepted && selectedSaveDirectory != null && selectedSaveDirectory.isDirectory()) {
                                negotiatedThreadCount = Math.min(clientAnnouncedThreads, ITHREADS);
                                negotiatedThreadCount = Math.max(1, negotiatedThreadCount);
                                decisionMessage = "OK@" + negotiatedThreadCount;
                                proceedWithTransfer = true;
                                LogPanel.log("FileReceiver: User accepted. Sending " + decisionMessage);
                            } else {
                                decisionMessage = "REJECT";
                                LogPanel.log("FileReceiver: User rejected, no save path, or path is not a directory. Sending REJECT.");
                                if (selectedSaveDirectory != null && !selectedSaveDirectory.isDirectory()) {
                                    LogPanel.log("Error: Selected save path is not a directory: " + selectedSaveDirectory.getAbsolutePath());
                                }
                            }
                            dos.writeUTF(decisionMessage);
                            dos.flush();

                            if (proceedWithTransfer) {
                                LogPanel.log("FileReceiver: Waiting for sender's ACK to our '" + decisionMessage + "' message...");
                                String senderAckToOk = dis.readUTF();
                                if (!"ACK".equals(senderAckToOk)) {
                                    LogPanel.log("Error: Sender did not ACK our OK@ message. Received: '" + senderAckToOk + "'. Aborting this transfer.");
                                    proceedWithTransfer = false;
                                } else {
                                    LogPanel.log("FileReceiver: Sender ACKed our OK@ message. Ready for actual filename.");
                                    try {
                                        outPutFileName = dis.readUTF(); // Read the actual filename to be saved (e.g., archive.tar.lz4)
                                        LogPanel.log("FileReceiver: Received final filename from sender: " + outPutFileName);

                                        if (outPutFileName == null || outPutFileName.trim().isEmpty()) {
                                            throw new IOException("Received null or empty filename from sender.");
                                        }
                                        // Validate filename for path traversal or invalid characters (basic example)
                                        if (outPutFileName.contains("..") || outPutFileName.contains("/") || outPutFileName.contains("\\")) {
                                            throw new IOException("Received potentially unsafe filename: " + outPutFileName);
                                        }

                                        wholeOutputFilePath = selectedSaveDirectory.getAbsolutePath() + File.separator + outPutFileName;
                                        if (outPutFileName.endsWith(".tar.lz4") && outPutFileName.length() > 8) { // ".tar.lz4" is 8 chars
                                            // Output folder for decompression will be the selected save directory
                                            decompressedOutputFolderPath = selectedSaveDirectory.getAbsolutePath();
                                        } else {
                                            decompressedOutputFolderPath = wholeOutputFilePath; // Not a tar.lz4 or too short
                                        }
                                        
                                        LogPanel.log("FileReceiver: Calculated final save path: " + wholeOutputFilePath);
                                        if (decompressedOutputFolderPath != null && !decompressedOutputFolderPath.equals(wholeOutputFilePath)) {
                                            LogPanel.log("FileReceiver: Decompression target folder: " + decompressedOutputFolderPath);
                                        }


                                        dos.writeUTF("ACK"); // ACK the filename
                                        dos.flush();
                                        LogPanel.log("FileReceiver: ACKed filename. Handshake fully complete. Preparing for data sockets.");
                                    } catch (Exception e_fn_ack) { // Catch any error during filename processing/ACK
                                        LogPanel.log("FileReceiver: Error processing received filename or sending its ACK: " + e_fn_ack.getClass().getSimpleName() + " - " + e_fn_ack.getMessage());
                                        // e_fn_ack.printStackTrace(); // For detailed debug
                                        proceedWithTransfer = false; // Critical: ensure we don't proceed
                                        // Do not try to send error to sender, socket state might be bad. Sender will get EOF.
                                    }
                                }
                            }

                            if (proceedWithTransfer) {
                                LogPanel.log("FileReceiver: Initializing Receiver module for data transfer...");
                                if (callback != null) callback.onStart(currentTotalSize);

                                Receiver dataReceiver = new Receiver(serverSocket);
                                LogPanel.log("FileReceiver: Starting data reception process for " + wholeOutputFilePath + "...");
                                boolean receptionWasSuccessful = false;
                                try {
                                    receptionWasSuccessful = dataReceiver.start(wholeOutputFilePath, currentTotalSize, negotiatedThreadCount, callback);
                                    if (receptionWasSuccessful) {
                                        LogPanel.log("FileReceiver: Data reception successful for: " + wholeOutputFilePath);
                                        if (outPutFileName.endsWith(".tar.lz4") && decompressedOutputFolderPath != null) {
                                            LogPanel.log("FileReceiver: Decompressing " + wholeOutputFilePath + " into folder " + decompressedOutputFolderPath);
                                            // Ensure the target for decompression is a directory.
                                            // LZ4FileDecompressor.decompressTarLz4Folder expects outputParentDir
                                            LZ4FileDecompressor.decompressTarLz4Folder(wholeOutputFilePath, decompressedOutputFolderPath);
                                            LogPanel.log("FileReceiver: Decompression complete into " + decompressedOutputFolderPath);
                                            // Optionally delete the .tar.lz4 file after successful decompression
                                            // new File(wholeOutputFilePath).delete();
                                        }
                                        if (callback != null) callback.onComplete();
                                    } else {
                                        LogPanel.log("FileReceiver: Data reception process reported failure for " + outPutFileName);
                                        // Receiver.start or its workers should have called callback.onError
                                    }
                                } catch (InterruptedException e_intr) {
                                    Thread.currentThread().interrupt();
                                    LogPanel.log("FileReceiver: Data reception interrupted for " + outPutFileName + ": " + e_intr.getMessage());
                                    if (callback != null) callback.onError(e_intr);
                                } catch (Exception e_recv) {
                                    LogPanel.log("FileReceiver: Error during data reception/decompression for " + outPutFileName + ": " + e_recv.getClass().getName() + " - " + e_recv.getMessage());
                                    // e_recv.printStackTrace();
                                    if (callback != null) callback.onError(e_recv);
                                }
                                LogPanel.log("FileReceiver: Finished handling current sender (" + currentSenderName + "). Ready for next handshake.");
                            } else {
                                LogPanel.log("FileReceiver: Handshake failed or transfer rejected. Not proceeding to data reception for this attempt.");
                            }
                        } else {
                            LogPanel.log("Error: Invalid handshake string received (no '@'): " + handShakeMsg);
                            dos.writeUTF("ERROR_INVALID_HANDSHAKE");
                            dos.flush();
                        }
                    } // Streams dis/dos are closed here.
                } catch (SocketTimeoutException e) {
                    LogPanel.log("FileReceiver: Timeout during handshake phase with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getMessage());
                    if (callback != null && currentTotalSize != null) callback.onError(e);
                } catch (EOFException e) {
                    LogPanel.log("FileReceiver: EOF during handshake with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ". Client likely disconnected. " + e.getMessage());
                    if (callback != null && currentTotalSize != null) callback.onError(e);
                } catch (IOException e) {
                    LogPanel.log("FileReceiver: IOException during handshake phase with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getMessage());
                    // e.printStackTrace();
                    if (callback != null && currentTotalSize != null) callback.onError(e);
                } catch (Exception e) { // Catch-all for other handshake processing errors
                    LogPanel.log("FileReceiver: General error during handshake processing with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    // e.printStackTrace();
                    if (callback != null && currentTotalSize != null) callback.onError(e);
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
            } // End while(true)
        } // ServerSocket closed here
        // LogPanel.log("FileReceiver: Main server loop has exited (this should not happen for a continuous server).");
    }

    // FileReceiveDialog class (ensure Main.GUI and SendFileGUI.formatFileSize are accessible and correct)
    private class FileReceiveDialog extends JDialog {
        private boolean accepted = false;
        public FileReceiveDialog(JFrame owner, StringBuilder fileListBuilder, String senderName, String totalSize) {
            super(owner, "Incoming Files from " + senderName, true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(450, 350);
            setLocationRelativeTo(owner); // owner is Main.GUI
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
            infoPanel.add(new JLabel("Total Size: " + totalSize)); // totalSize is formatted string
            topPanel.add(infoPanel, BorderLayout.CENTER);
            content.add(topPanel, BorderLayout.NORTH);
            DefaultListModel<String> listModel = new DefaultListModel<>();
            String[] files = fileListBuilder.toString().split("\\r?\\n");
            for (String file : files) {
                if (file != null && !file.trim().isEmpty()) {
                    listModel.addElement(file);
                }
            }
            JList<String> fileList = new JList<>(listModel);
            fileList.setBorder(BorderFactory.createTitledBorder("Files to Receive"));
            JScrollPane scroll = new JScrollPane(fileList);
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
        }
        public boolean showDialog() {
            setVisible(true);
            return accepted;
        }
    }
}