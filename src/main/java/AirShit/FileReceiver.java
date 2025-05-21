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

import AirShit.Main.SEND_STATUS;
import AirShit.ui.LogPanel;

/**
 * Receiver: 在指定埠口接收多個執行緒送過來的檔案分段，並寫入同一個檔案中。
 */
public class FileReceiver {

    public int port;

    private final int ITHREADS = Runtime.getRuntime().availableProcessors();
    private final String THREADS = Integer.toString(ITHREADS);
    private Receiver receiver;
    public String SenderName;
    public Long total_size;

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 10; // 握手超時時間 (秒)
    private static final int MAX_HANDSHAKE_RETRIES = 3; // 最大重試次數

    FileReceiver(int port) {
        this.port = port;
    }

    public static void println(String str) {
        // System.out.println(str); // Replaced by LogPanel
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    public void start(TransferCallback callback) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        LogPanel.log("FileReceiver started on port: " + port);

        while (true) {
            try (Socket socket = serverSocket.accept();
            DataInputStream dis = new DataInputStream(socket.getInputStream()); 
            DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                socket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000);


                String handShake = dis.readUTF();
                if(handShake.contains("@")) {
                        // is hand shake
                        // senderName@fileNames@threads@total_file_size 
                        // or 
                        // senderName@fileName@threads@total_file_size
                        try {
                            String[] parts = handShake.split("@");
                            StringBuilder sb = new StringBuilder();
                            int len = parts.length;
                            
                            if (len < 3) {
                                LogPanel.log("Error: Invalid handshake format - not enough parts. Received: " + handShake);
                                dos.writeUTF("ERROR_HANDSHAKE_FORMAT");
                                dos.flush();
                                continue;
                            }
                            SenderName = parts[0];
                            
                            total_size = Long.parseLong(parts[len - 1]);
                            int threads = Integer.parseInt(parts[len - 2]);
                            for(int i = 1 ; i < len - 2 ; i++) {
                                sb.append(parts[i]);
                                if (i < len - 3) { // 如果不是倒數第二個檔名，則加換行符
                                    sb.append("\n");
                                }                            
                            }
                            // 如果有收到，先回ACK
                            dos.writeUTF("ACK");
                            dos.flush();

                            // show pannel
                            FileReceiveDialog dialog = new FileReceiveDialog(Main.GUI ,  sb ,  SenderName,  Long.toString(total_size));
                            boolean accepted = dialog.showDialog();
                            
                            String message;
                            int threadCount = Math.min(threads, ITHREADS);
                            
                            File selectedSavePath = null; // To store the chosen save directory
                            


                            // 選擇存放路徑
                            if (accepted) {
                                // User accepted the transfer, now ask for save location
                                JFileChooser saveLocationChooser = new JFileChooser();
                                saveLocationChooser.setDialogTitle("Select Save Location for files from " + SenderName);
                                saveLocationChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                                saveLocationChooser.setAcceptAllFileFilterUsed(false); // Don't allow "All Files" filter

                                // Set a default directory (e.g., user's Downloads or home directory)
                                File defaultSaveDir = new File(System.getProperty("user.home") + File.separator + "Downloads");
                                if (!defaultSaveDir.exists()) {
                                    defaultSaveDir.mkdirs(); // Create if it doesn't exist
                                }
                                saveLocationChooser.setCurrentDirectory(defaultSaveDir);

                                // Show the dialog. Main.GUI should be the parent JFrame.
                                int chooserResult = saveLocationChooser.showDialog(Main.GUI, "Select Folder");

                                if (chooserResult == JFileChooser.APPROVE_OPTION) {
                                    selectedSavePath = saveLocationChooser.getSelectedFile();
                                    LogPanel.log("User selected save path: " + selectedSavePath.getAbsolutePath());
                                    // 'accepted' remains true
                                } else {
                                    LogPanel.log("User cancelled save location selection.");
                                    accepted = false; // Treat as rejection if no save path is chosen
                                }
                            }


                            // 回復客戶選擇
                            if (accepted && selectedSavePath != null) { // Must be accepted AND have a save path
                                message = "OK@" + Integer.toString(threadCount);
                            } else {
                                message = "REJECT";
                                accepted = false; // Ensure accepted is false if we are rejecting
                            }

                            boolean isFine = false;
                            dos.writeUTF(message); // Send OK@threads or REJECT
                            dos.flush();
                            int retries = 0;

                            while (accepted && !isFine && (retries < MAX_HANDSHAKE_RETRIES + 2)) { // Only wait for ACK if we sent "OK@"
                                LogPanel.log("Waiting for ACK from sender...");
                                // Timeout for ACK read is already set by socket.setSoTimeout()
                                try {
                                    String res = dis.readUTF(); // Wait for ACK

                                    LogPanel.log("Received from sender: " + res);
                                    if (res.equals("ACK")) {
                                        isFine = true; // Handshake fully completed
                                        LogPanel.log("ACK received. Ready for file data.");
                                        break;
                                    } else {
                                        LogPanel.log("Error: Expected ACK, but received: " + res);
                                        // isFine remains false
                                        continue;
                                    }

                                } catch (SocketTimeoutException e) {
                                    LogPanel.log("Error: Timeout waiting for ACK from sender.");
                                    // isFine remains false
                                } catch (IOException e) {
                                    LogPanel.log("Error: IOException waiting for ACK: " + e.getMessage());
                                    // isFine remains false
                                }
                                retries++;
                            }

                            if(!isFine || !accepted) continue;
                            
                            try {
                                String outPutFileName = dis.readUTF();
                                String wholeOutputFile = selectedSavePath.getAbsolutePath()+"\\"+outPutFileName;
                                String OutputFile = selectedSavePath.getAbsolutePath()+"\\"+outPutFileName.substring(0, outPutFileName.length()-8);
                                // System.out.println(selectedSavePath.getAbsolutePath()+"\\"+outPutFileName);
                                dos.writeUTF("ACK");

                                callback.onStart(total_size);
                                receiver = new Receiver(serverSocket);
                                Thread receiverThread = new Thread(() -> {
                                    boolean receptionWasSuccessful = false;
                                    try {
                                        LogPanel.log("ReceiverThread: Starting file reception for: " + wholeOutputFile);
                                        LogPanel.log("ReceiverThread: Expected total size: " + total_size);
                                        LogPanel.log("ReceiverThread: Negotiated thread count: " + threadCount);
                                        
                                        // CRITICAL FIX 1: Use total_size for fileLength
                                        receptionWasSuccessful = receiver.start(wholeOutputFile, total_size, threadCount, callback);

                                        if (receptionWasSuccessful) {
                                            LogPanel.log("ReceiverThread: File reception successful: " + wholeOutputFile);

                                            if (outPutFileName.endsWith(".tar.lz4")) {
                                                LogPanel.log("ReceiverThread: Attempting to decompress '" + wholeOutputFile + "' into '" + OutputFile + "'");
                                                
                                                boolean decompress_success = LZ4FileDecompressor.decompressTarLz4Folder(wholeOutputFile, OutputFile);
                                                
                                                if (decompress_success) {
                                                    LogPanel.log("ReceiverThread: Decompression successful!");
                                                    // Optionally delete the archive after successful decompression
                                                    // if (new File(wholeOutputFile).delete()) {
                                                    //     LogPanel.log("ReceiverThread: Deleted archive " + wholeOutputFile);
                                                    // } else {
                                                    //     LogPanel.log("ReceiverThread: Failed to delete archive " + wholeOutputFile);
                                                    // }
                                                } else {
                                                    LogPanel.log("ReceiverThread: Decompression failed for " + wholeOutputFile);
                                                    // Consider this an error state for the overall transfer
                                                    // callback.onError(new IOException("Decompression failed for " + outPutFileName));
                                                    // To prevent calling onComplete if decompress fails, set receptionWasSuccessful to false or handle differently
                                                }
                                            } else {
                                                LogPanel.log("ReceiverThread: File '" + outPutFileName + "' does not end with .tar.lz4, skipping decompression.");
                                            }
                                            
                                            // Only call onComplete if everything, including desired decompression, was successful
                                            // This depends on whether failed decompression is a total failure or not.
                                            // For now, assuming onComplete is for successful reception.
                                            LogPanel.log("ReceiverThread: Processing finished for " + outPutFileName);
                                            callback.onComplete();

                                        } else {
                                            LogPanel.log("ReceiverThread: File reception failed for " + wholeOutputFile + " (receiver.start returned false). Decompression skipped.");
                                            // callback.onError() should have been called by receiver.start()
                                        }

                                    } catch (IOException | InterruptedException e) {
                                        LogPanel.log("ReceiverThread: Error during file reception/decompression: " + e.getMessage());
                                        if (callback != null) {
                                            callback.onError(e);
                                        }
                                    } catch (Exception e) { // Catch any other unexpected exceptions
                                        LogPanel.log("ReceiverThread: Unexpected error: " + e.getClass().getName() + " - " + e.getMessage());
                                        e.printStackTrace(); // Good for debugging
                                        if (callback != null) {
                                            callback.onError(e);
                                        }
                                    }
                                });
                                receiverThread.setName("FileSender-SendFile-Thread"); // Good practice to name threads
                                receiverThread.run(); // Correct way to start a new thread

                            } catch (Exception e) {
                                callback.onError(new Exception("transfer fail", e));
                                continue;
                            }
                            
                        } catch (Exception e) {
                            println(e.toString());
                            callback.onError(e);
                            continue;
                        }
                    }
            } catch (Exception e) {
                println(e.toString());
                callback.onError(e);
                continue;
            }
            
        }
    }

    private class FileReceiveDialog extends JDialog {
        private boolean accepted = false;

        /**
         * Constructs the dialog.
         * 
         * @param owner           the parent JFrame
         * @param fileListBuilder a StringBuilder containing file names separated by
         *                        newlines
         * @param senderName      name of the sender
         * @param totalSize       total size of files (e.g., "2.3 MB")
         */
        public FileReceiveDialog(JFrame owner, StringBuilder fileListBuilder, String senderName, String totalSize) {
            super(owner, "Incoming Files from " + senderName, true);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(450, 350);
            setLocationRelativeTo(owner);

            // Main content panel
            JPanel content = new JPanel(new BorderLayout(10, 10));
            content.setBorder(new EmptyBorder(15, 15, 15, 15));
            setContentPane(content);

            // Top: Sender info and icon
            JPanel topPanel = new JPanel(new BorderLayout(8, 8));
            JLabel iconLabel = new JLabel();
            // You can customize this icon path
            java.net.URL iconUrl = this.getClass().getResource("/asset/data-transfer.png");

            if (iconUrl != null) { // 先檢查 URL 是否為 null
                ImageIcon dataIcon = new ImageIcon(iconUrl);
                // 可以進一步檢查圖片是否真的載入成功 (例如 dataIcon.getImageLoadStatus())
                if (dataIcon.getImageLoadStatus() == MediaTracker.COMPLETE) {
                    Image image = dataIcon.getImage();
                    int targetSize = 30; // 設定目標圖示大小
                    Image scaled = image.getScaledInstance(targetSize, targetSize, Image.SCALE_SMOOTH);
                    iconLabel.setIcon(new ImageIcon(scaled));
                } else {
                    // 圖片載入失敗的處理 (例如，設定預設圖示或文字)
                    System.err.println("Failed to load image: /asset/data-transfer.png");
                    iconLabel.setText("[X]"); // 示意圖示載入失敗
                }
            } else {
                // 資源 URL 為 null 的處理 (圖片檔案找不到)
                System.err.println("Could not find resource: /asset/data-transfer.png. Place it in src/main/resources/asset/");
                iconLabel.setText("[?]"); // 示意找不到資源
            }

            topPanel.add(iconLabel, BorderLayout.WEST);
            JPanel infoPanel = new JPanel(new GridLayout(2, 1));
            infoPanel.add(new JLabel("Sender: " + senderName));
            infoPanel.add(new JLabel("Total Size: " + totalSize));
            topPanel.add(infoPanel, BorderLayout.CENTER);
            content.add(topPanel, BorderLayout.NORTH);

            // Center: File list
            DefaultListModel<String> listModel = new DefaultListModel<>();
            String[] files = fileListBuilder.toString().split("\\r?\\n");
            for (String file : files) {
                listModel.addElement(file);
            }
            JList<String> fileList = new JList<>(listModel);
            fileList.setBorder(BorderFactory.createTitledBorder("Files to Receive"));
            JScrollPane scroll = new JScrollPane(fileList);
            content.add(scroll, BorderLayout.CENTER);

            // Bottom: Buttons panel
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton acceptBtn = new JButton("Accept");
            JButton rejectBtn = new JButton("Reject");

            acceptBtn.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    accepted = true;
                    dispose();
                }
            });
            rejectBtn.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    accepted = false;
                    dispose();
                }
            });
            buttonPanel.add(rejectBtn);
            buttonPanel.add(acceptBtn);
            content.add(buttonPanel, BorderLayout.SOUTH);
        }

        /**
         * Shows the dialog and returns whether the user accepted.
         * 
         * @return true if accepted, false otherwise
         */
        public boolean showDialog() {
            setVisible(true);
            return accepted;
        }

        // Example usage
        // public static void main(String[] args) {
        //     SwingUtilities.invokeLater(() -> {
        //         StringBuilder sb = new StringBuilder();
        //         sb.append("document.pdf\n");
        //         sb.append("photo.jpg\n");
        //         sb.append("archive.zip");

        //         FileReceiveDialog dialog = new FileReceiveDialog(null, sb, "Alice", "4.8 MB");
        //         boolean result = dialog.showDialog();
        //         System.out.println("User accepted? " + result);
        //         System.exit(0);
        //     });
        // }
    }

}