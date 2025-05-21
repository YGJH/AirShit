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
                            SenderName = parts[0];
                            total_size = Long.parseLong(parts[len - 1]);
                            int threads = Integer.parseInt(parts[len - 2]);
                            for(int i = 1 ; i < len - 2 ; i++) {
                                sb.append(parts[i]);
                                if (i < len - 3) { // 如果不是倒數第二個檔名，則加換行符
                                    sb.append("\n");
                                }                            }
                            // show pannel
                            FileReceiveDialog dialog = new FileReceiveDialog(Main.GUI ,  sb ,  SenderName,  Long.toString(total_size));
                            boolean accepted = dialog.showDialog();
                            
                            int retries = 0;
                            String message;
                            int threadCount = Math.min(threads, ITHREADS);
                            if(accepted) {
                                message = "OK@" + Integer.toString(threadCount);
                            } else {
                                message = "REJECT";
                            }
                            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
                            boolean isFine = false;
                            while(retries < MAX_HANDSHAKE_RETRIES) {
                                try {
                                    dos.writeUTF(message);
                                    dos.flush();
                                    String res = dis.readUTF();
                                    if(res.equals("ACK")) {
                                        accepted = true;
                                        isFine = true;
                                        break;
                                    }
                                } catch (SocketTimeoutException e) {
                                    continue;
                                } catch (IOException e) {
                                    // e.printStackTrace(); // 可選
                                    // handshakeAccepted 仍然是 false，會進入下一次重試
                                    if (retries >= MAX_HANDSHAKE_RETRIES -1 && callback != null) { // 如果是最後一次重試失敗
                                        callback.onError(new IOException("握手失敗，已達最大重試次數", e));
                                    }
                                } finally {
                                    retries++;
                                }
                            }
                            if(!isFine || !accepted) continue;
                            
                            try {
                                callback.onStart(total_size);
                                receiver = new Receiver(serverSocket);
                            } catch (Exception e) {
                                callback.onError(new Exception("transfer fail", e));
                                continue;
                            }
                            callback.onComplete();
                            
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
            iconLabel.setIcon(new ImageIcon(getClass().getResource("/icons/file_transfer.png")));
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