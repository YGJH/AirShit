package AirShit;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;

import AirShit.Main.SEND_STATUS; // Assuming this import is correct for your project structure
import AirShit.ui.LogPanel;

public class FileReceiver {

    public int port;
    private final int ITHREADS = Runtime.getRuntime().availableProcessors();
    public String currentSenderName;
    public Long currentTotalSize;

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 10; // Timeout for individual read operations
    private static final int MAX_HANDSHAKE_ATTEMPTS = 1; // How many times to retry the whole handshake accept if it fails early

    FileReceiver(int port) {
        this.port = port;
    }

    public static void println(String str) { // Utility, consider removing if LogPanel is always used
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    public void start(TransferCallback callback) throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            LogPanel.log("FileReceiver started on port: " + port + ". Waiting for senders...");

            while (true) {
                Socket handshakeSocket = null;
                String outPutFileName = null;
                String wholeOutputFile = null;
                String decompressedOutputFile = null;
                int negotiatedThreadCount = 1;

                // Reset per-sender state
                currentSenderName = null;
                currentTotalSize = null;

                try {
                    LogPanel.log("FileReceiver: Waiting for a new sender to connect for handshake...");
                    handshakeSocket = serverSocket.accept();
                    LogPanel.log("FileReceiver: Accepted handshake connection from " + handshakeSocket.getRemoteSocketAddress());
                    handshakeSocket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000); // Timeout for read operations on this socket

                    try (DataInputStream dis = new DataInputStream(handshakeSocket.getInputStream());
                         DataOutputStream dos = new DataOutputStream(handshakeSocket.getOutputStream())) {

                        String handShakeMsg = dis.readUTF();
                        LogPanel.log("FileReceiver: Received handshake message: " + handShakeMsg);

                        if (handShakeMsg.contains("@")) {
                            String[] parts = handShakeMsg.split("@");
                            if (parts.length < 4) {
                                LogPanel.log("Error: Invalid handshake format - not enough parts. Received: " + handShakeMsg);
                                dos.writeUTF("ERROR_HANDSHAKE_FORMAT");
                                dos.flush();
                                continue;
                            }

                            currentSenderName = parts[0];
                            String clientAnnouncedFileName = parts[1];
                            int clientAnnouncedThreads = Integer.parseInt(parts[parts.length - 2]);
                            currentTotalSize = Long.parseLong(parts[parts.length - 1]);

                            LogPanel.log(String.format("FileReceiver: Parsed Handshake: Sender=%s, File(s)=%s, Threads=%d, Size=%d",
                                    currentSenderName, clientAnnouncedFileName, clientAnnouncedThreads, currentTotalSize));

                            dos.writeUTF("ACK");
                            dos.flush();
                            LogPanel.log("FileReceiver: Sent ACK for initial handshake.");

                            FileReceiveDialog dialog = new FileReceiveDialog(Main.GUI, new StringBuilder(clientAnnouncedFileName), currentSenderName, SendFileGUI.formatFileSize(currentTotalSize));
                            boolean userAccepted = dialog.showDialog();
                            File selectedSavePath = null;

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
                                    selectedSavePath = saveLocationChooser.getSelectedFile();
                                    LogPanel.log("User selected save path: " + selectedSavePath.getAbsolutePath());
                                } else {
                                    LogPanel.log("User cancelled save location selection.");
                                    userAccepted = false;
                                }
                            }

                            String decisionMessage;
                            boolean proceedWithTransfer = false;

                            if (userAccepted && selectedSavePath != null) {
                                negotiatedThreadCount = Math.min(clientAnnouncedThreads, ITHREADS);
                                negotiatedThreadCount = Math.max(1, negotiatedThreadCount);
                                decisionMessage = "OK@" + negotiatedThreadCount;
                                proceedWithTransfer = true;
                                LogPanel.log("FileReceiver: User accepted. Sending " + decisionMessage);
                            } else {
                                decisionMessage = "REJECT";
                                LogPanel.log("FileReceiver: User rejected or no save path. Sending REJECT.");
                            }
                            dos.writeUTF(decisionMessage);
                            dos.flush();

                            if (proceedWithTransfer) {
                                LogPanel.log("FileReceiver: Waiting for sender's ACK to our '" + decisionMessage + "' message...");
                                String senderAckToOk = dis.readUTF(); // Expect "ACK" from sender
                                if (!senderAckToOk.equals("ACK")) {
                                    LogPanel.log("Error: Sender did not ACK our OK@ message. Received: '" + senderAckToOk + "'. Aborting this transfer.");
                                    proceedWithTransfer = false; // Critical: if sender doesn't ACK, we don't proceed
                                } else {
                                    LogPanel.log("FileReceiver: Sender ACKed our OK@ message. Ready for actual filename.");
                                    outPutFileName = dis.readUTF();
                                    LogPanel.log("FileReceiver: Received final filename from sender: " + outPutFileName);

                                    wholeOutputFile = selectedSavePath.getAbsolutePath() + File.separator + outPutFileName;
                                    if (outPutFileName.endsWith(".tar.lz4") && outPutFileName.length() > 8) {
                                        decompressedOutputFile = selectedSavePath.getAbsolutePath() + File.separator + outPutFileName.substring(0, outPutFileName.length() - 8);
                                    } else {
                                        decompressedOutputFile = wholeOutputFile;
                                    }
                                    dos.writeUTF("ACK"); // ACK the filename
                                    dos.flush();
                                    LogPanel.log("FileReceiver: ACKed filename. Handshake fully complete. Preparing for data sockets.");
                                }
                            }

                            if (proceedWithTransfer) {
                                LogPanel.log("FileReceiver: Initializing Receiver module for data transfer...");
                                if (callback != null) callback.onStart(currentTotalSize);

                                Receiver dataReceiver = new Receiver(serverSocket);
                                LogPanel.log("FileReceiver: Starting data reception process for " + wholeOutputFile + "...");
                                boolean receptionWasSuccessful = false;
                                try {
                                    receptionWasSuccessful = dataReceiver.start(wholeOutputFile, currentTotalSize, negotiatedThreadCount, callback);
                                    if (receptionWasSuccessful) {
                                        LogPanel.log("FileReceiver: Data reception successful for: " + wholeOutputFile);
                                        if (outPutFileName.endsWith(".tar.lz4")) {
                                            LogPanel.log("FileReceiver: Decompressing " + wholeOutputFile + " to " + decompressedOutputFile);
                                            LZ4FileDecompressor.decompressTarLz4Folder(wholeOutputFile, decompressedOutputFile);
                                            LogPanel.log("FileReceiver: Decompression complete to " + decompressedOutputFile);
                                        }
                                        if (callback != null) callback.onComplete();
                                    } else {
                                        LogPanel.log("FileReceiver: Data reception process reported failure for " + outPutFileName);
                                    }
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    LogPanel.log("FileReceiver: Data reception interrupted for " + outPutFileName + ": " + e.getMessage());
                                    if (callback != null) callback.onError(e);
                                } catch (Exception e) {
                                    LogPanel.log("FileReceiver: Error during data reception/decompression for " + outPutFileName + ": " + e.getClass().getName() + " - " + e.getMessage());
                                    if (callback != null) callback.onError(e);
                                }
                                LogPanel.log("FileReceiver: Finished handling current sender (" + currentSenderName + "). Ready for next handshake.");
                            } else {
                                LogPanel.log("FileReceiver: Handshake failed or transfer rejected. Not proceeding to data reception for this attempt.");
                                // If proceedWithTransfer is false here, the sender (waiting for filename ACK) will get EOF.
                            }
                        } else {
                            LogPanel.log("Error: Invalid handshake string received (no '@'): " + handShakeMsg);
                            dos.writeUTF("ERROR_INVALID_HANDSHAKE");
                            dos.flush();
                        }
                    } // Streams dis/dos are closed here. If proceedWithTransfer was false, sender might get EOF.
                } catch (SocketTimeoutException e) {
                    LogPanel.log("FileReceiver: Timeout during handshake phase with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getMessage());
                    if (callback != null && currentTotalSize != null) callback.onError(e);
                } catch (EOFException e) {
                    LogPanel.log("FileReceiver: EOF during handshake with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ". Client likely disconnected. " + e.getMessage());
                    if (callback != null && currentTotalSize != null) callback.onError(e);
                } catch (IOException e) {
                    LogPanel.log("FileReceiver: IOException during handshake phase with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getMessage());
                    if (callback != null && currentTotalSize != null) callback.onError(e);
                } catch (Exception e) {
                    LogPanel.log("FileReceiver: General error during handshake processing with " + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client") + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
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
            }
        }
        // LogPanel.log("FileReceiver: Main start method is exiting (should not happen in continuous server).");
    }

    // FileReceiveDialog class (assuming it's correct and as provided before)
    private class FileReceiveDialog extends JDialog {
        private boolean accepted = false;
        public FileReceiveDialog(JFrame owner, StringBuilder fileListBuilder, String senderName, String totalSize) {
            super(owner, "Incoming Files from " + senderName, true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(450, 350);
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
            infoPanel.add(new JLabel("Total Size: " + totalSize));
            topPanel.add(infoPanel, BorderLayout.CENTER);
            content.add(topPanel, BorderLayout.NORTH);
            DefaultListModel<String> listModel = new DefaultListModel<>();
            String[] files = fileListBuilder.toString().split("\\r?\\n");
            for (String file : files) {
                if (file != null && !file.trim().isEmpty()) { // Avoid adding empty strings
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