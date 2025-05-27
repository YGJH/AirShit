package AirShit;

import java.awt.*;
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
import AirShit.ui.FXFileChooserAdapter;
import AirShit.ui.FileChooserDialog;
import AirShit.ui.*;
public class FileReceiver {

    public int port;
    private final int ITHREADS = Runtime.getRuntime().availableProcessors() * 4;
    private File selectedSaveDirectory;

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 30;

    // Simple POJO to store received file information
    private static class FileInfo {
        String name;
        long size;

        FileInfo(String name, long size) {
            this.name = name;
            this.size = size;
        }

        @Override
        public String toString() {
            return name + " (" + SendFileGUI.formatFileSize(size) + ")";
        }
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

                try {
                    // Accept connection with timeout
                    serverSocket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000);
                    handshakeSocket = serverSocket.accept();
                    handshakeSocket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000);

                    LogPanel.log("FileReceiver: Client connected from " + handshakeSocket.getRemoteSocketAddress()
                            + ". Starting handshake...");

                    try (DataInputStream handshakeIn = new DataInputStream(handshakeSocket.getInputStream());
                         DataOutputStream handshakeOut = new DataOutputStream(handshakeSocket.getOutputStream())) {

                        // Read file count
                        int fileCount = handshakeIn.readInt();
                        LogPanel.log("FileReceiver: Expecting " + fileCount + " files");

                        // Read file information
                        long totalSizeFromSender = 0;
                        for (int i = 0; i < fileCount; i++) {
                            String fileName = handshakeIn.readUTF();
                            long fileSize = handshakeIn.readLong();
                            filesExpected.add(new FileInfo(fileName, fileSize));
                            totalSizeFromSender += fileSize;
                            LogPanel.log("FileReceiver: File " + (i + 1) + ": " + fileName + " (" 
                                    + SendFileGUI.formatFileSize(fileSize) + ")");
                        }

                        LogPanel.log("FileReceiver: Total expected size: " + SendFileGUI.formatFileSize(totalSizeFromSender));

                        // Read thread count from sender
                        int senderThreadCount = handshakeIn.readInt();
                        int negotiatedThreadCount = Math.min(senderThreadCount, ITHREADS);
                        LogPanel.log("FileReceiver: Sender suggests " + senderThreadCount + " threads, using " 
                                + negotiatedThreadCount);

                        // Show file receive dialog
                        FileReceiveDialog dialog = new FileReceiveDialog(filesExpected, totalSizeFromSender);
                        boolean proceedWithTransfer = dialog.showDialog();

                        if (proceedWithTransfer && selectedSaveDirectory != null) {
                            // Send acceptance
                            handshakeOut.writeBoolean(true);
                            handshakeOut.writeInt(negotiatedThreadCount);
                            handshakeOut.flush();
                            LogPanel.log("FileReceiver: Handshake completed. Transfer accepted.");
                        } else {
                            // Send rejection
                            handshakeOut.writeBoolean(false);
                            handshakeOut.writeInt(0);
                            handshakeOut.flush();
                            LogPanel.log("FileReceiver: Transfer rejected by user.");
                            continue;
                        }

                        System.out.println("negotiatedThreadCount: " + negotiatedThreadCount);

                        // Data Reception Loop (if proceedWithTransfer is true)
                        if (proceedWithTransfer) {
                            LogPanel.log("FileReceiver: Initializing Receiver module for data transfer...");

                            boolean overallSuccess = true;
                            final int totalFiles = filesExpected.size();
                            int fileIndex = 0;
                            
                            for (FileInfo currentFileToReceive : filesExpected) {
                                fileIndex++;
                                final int currentFileIndex = fileIndex;
                                String outputFileName = currentFileToReceive.name;
                                long fileSizeForThisFile = currentFileToReceive.size;
                                String wholeOutputFilePath = selectedSaveDirectory.getAbsolutePath() + File.separator
                                        + outputFileName;

                                LogPanel.log("FileReceiver: Starting data reception for " + outputFileName + " -> "
                                        + wholeOutputFilePath + " (" + SendFileGUI.formatFileSize(fileSizeForThisFile)
                                        + ")");
                                        
                                // Call onFileStart callback
                                if (callback != null) {
                                    callback.onFileStart(currentFileIndex, totalFiles, outputFileName);
                                }

                                File outputFile = new File(wholeOutputFilePath);
                                if (outputFile.exists() == false) {
                                    outputFile.getParentFile().mkdirs();
                                    outputFile.createNewFile();
                                    LogPanel.log("FileReceiver: Created new file: " + wholeOutputFilePath);
                                }

                                ReceiverOptimized dataReceiver = new ReceiverOptimized(serverSocket);
                                boolean receptionWasSuccessful = false;
                                try {
                                    receptionWasSuccessful = dataReceiver.start(wholeOutputFilePath,
                                            fileSizeForThisFile, negotiatedThreadCount, callback);
                                    if (receptionWasSuccessful) {
                                        LogPanel.log(
                                                "FileReceiver: Data reception successful for: " + wholeOutputFilePath);
                                        if (outputFileName.endsWith(".tar")) {
                                            String decompressedTargetFolder = selectedSaveDirectory.getAbsolutePath();
                                            LogPanel.log("FileReceiver: Decompressing " + wholeOutputFilePath
                                                    + " into folder " + decompressedTargetFolder);
                                            try {
                                                TarExtractor.start(new File(wholeOutputFilePath),
                                                        new File(decompressedTargetFolder));
                                                LogPanel.log("FileReceiver: Decompression complete into "
                                                        + decompressedTargetFolder);
                                                try {
                                                    Files.deleteIfExists(Paths.get(wholeOutputFilePath));
                                                    LogPanel.log("FileReceiver: Deleted archive " + wholeOutputFilePath
                                                            + " after decompression.");
                                                } catch (IOException eDel) {
                                                    LogPanel.log("FileReceiver: Error deleting archive "
                                                            + wholeOutputFilePath + " after decompression: "
                                                            + eDel.getMessage());
                                                }
                                            } catch (Exception eDecompress) {
                                                LogPanel.log("FileReceiver: Error decompressing " + wholeOutputFilePath
                                                        + ": " + eDecompress.getMessage());
                                                if (callback != null)
                                                    callback.onError(new IOException(
                                                            "Decompression failed for " + outputFileName, eDecompress));
                                                overallSuccess = false;
                                            }
                                        }
                                        
                                        // Call onFileComplete callback for successful processing
                                        if (callback != null) {
                                            callback.onFileComplete(currentFileIndex, totalFiles, outputFileName);
                                        }
                                    } else {
                                        LogPanel.log("FileReceiver: Data reception process reported failure for "
                                                + outputFileName);
                                        if (callback != null)
                                            callback.onError(new IOException("Reception failed for " + outputFileName));
                                        overallSuccess = false;
                                        break;
                                    }
                                } catch (InterruptedException e_intr) {
                                    Thread.currentThread().interrupt();
                                    LogPanel.log("FileReceiver: Data reception interrupted for " + outputFileName + ": "
                                            + e_intr.getMessage());
                                    if (callback != null)
                                        callback.onError(e_intr);
                                    overallSuccess = false;
                                    break;
                                } catch (Exception e_recv) {
                                    LogPanel.log("FileReceiver: Error during data reception for " + outputFileName
                                            + ": " + e_recv.getClass().getName() + " - " + e_recv.getMessage());
                                    if (callback != null)
                                        callback.onError(e_recv);
                                    overallSuccess = false;
                                    break;
                                }
                            }

                            if (overallSuccess && callback != null) {
                                callback.onComplete();
                            } else if (!overallSuccess && callback != null) {
                                LogPanel.log(
                                        "FileReceiver: Overall multi-file transfer did not complete successfully.");
                            }

                        } else {
                            LogPanel.log(
                                    "FileReceiver: Handshake failed or transfer rejected. Not proceeding to data reception for this attempt.");
                            if (callback != null && totalSizeFromSender > 0) {
                                callback.onError(new IOException("Transfer rejected or handshake failed."));
                            } else if (callback != null) {
                                callback.onError(new IOException("Transfer not initiated."));
                            }
                        }
                    }
                } catch (SocketTimeoutException e) {
                    LogPanel.log("FileReceiver: Timeout during handshake phase with "
                            + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client")
                            + ": " + e.getMessage());
                    if (callback != null) {
                        callback.onError(e);
                    }
                } catch (IOException e) {
                    LogPanel.log("FileReceiver: IOException during handshake phase with "
                            + (handshakeSocket != null ? handshakeSocket.getRemoteSocketAddress() : "unknown client")
                            + ": " + e.getMessage());
                    if (callback != null) {
                        callback.onError(e);
                    }
                } finally {
                    if (handshakeSocket != null && !handshakeSocket.isClosed()) {
                        try {
                            handshakeSocket.close();
                        } catch (IOException e) {
                            LogPanel.log("FileReceiver: Error closing handshake socket: " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    // Dialog for showing files to be received and getting user confirmation
    private class FileReceiveDialog extends JDialog {
        private boolean accepted = false;

        public FileReceiveDialog(List<FileInfo> files, long totalSize) {
            setTitle("Incoming File Transfer");
            setModal(true);
            setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            
            JPanel content = new JPanel(new BorderLayout());
            content.setBorder(new EmptyBorder(15, 15, 15, 15));
            
            // Info panel
            JPanel infoPanel = new JPanel(new BorderLayout());
            
            String message = "Sender wants to transfer " + files.size() + " file(s) (" + 
                           SendFileGUI.formatFileSize(totalSize) + ")";
            JLabel messageLabel = new JLabel(message);
            messageLabel.setFont(messageLabel.getFont().deriveFont(Font.BOLD, 14f));
            infoPanel.add(messageLabel, BorderLayout.NORTH);
            
            // File list
            DefaultListModel<FileInfo> listModel = new DefaultListModel<>();
            for (FileInfo file : files) {
                listModel.addElement(file);
            }
            
            JList<FileInfo> fileList = new JList<>(listModel);
            fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            JScrollPane scrollPane = new JScrollPane(fileList);
            scrollPane.setPreferredSize(new Dimension(400, 150));
            
            infoPanel.add(new JLabel("Files to receive:"), BorderLayout.CENTER);
            content.add(infoPanel, BorderLayout.NORTH);
            content.add(scrollPane, BorderLayout.CENTER);
            
            // Buttons
            JPanel buttonPanel = new JPanel(new FlowLayout());
            JButton acceptBtn = new JButton("Accept");
            JButton rejectBtn = new JButton("Reject");
            
            acceptBtn.addActionListener(e -> {
                // Show directory chooser
                File selectedDir = FXFileChooserAdapter.showFileChooser();
                if (selectedDir != null) {
                    selectedSaveDirectory = selectedDir;
                    accepted = true;
                    LogPanel.log("User selected save directory: " + selectedSaveDirectory.getAbsolutePath());
                } else {
                    LogPanel.log("User cancelled save directory selection.");
                    accepted = false;
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
            pack();
        }

        public boolean showDialog() {
            setVisible(true);
            return accepted;
        }
    }
}
