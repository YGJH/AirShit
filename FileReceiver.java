package AirShit;

import java.awt.Dimension;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * Receiver: 在指定埠口接收多個執行緒送過來的檔案分段，並寫入同一個檔案中。
 */
public class FileReceiver {

    public int port;

    FileReceiver(int port) {
        this.port = port;
    }

    public static void println(String str) {
        System.out.println(str);
    }

    public void start(TransferCallback cb) throws IOException { // 此port 是你本地的port

        // handshake
        ServerSocket serverSocket = new ServerSocket(port);
        while (true) {
            // listen for handshake
            boolean isSingle = false;
            String senderUserName = null;
            String fileNames = null;
            String folderName = null;
            int fileCount = 0;
            long totalSize = 0;
            StringBuilder sb = new StringBuilder();
            try (
                    Socket socket = serverSocket.accept();
                    DataInputStream dis = new DataInputStream(socket.getInputStream());
                ) {
                String handshake = dis.readUTF();
                String[] parts = handshake.split("\\|");
                if (parts.length < 3) {
                    System.err.println("無效的 handshake 訊息： " + handshake);
                    continue;
                }
                // isSingle|SenderUserName|file.getName()|file.length();
                // isMulti|SenderUserName|folderName|file.getName()|file.length();
                println("接收到 handshake 訊息： " + handshake);
                if (parts[0].equals("isSingle")) {
                    isSingle = true;
                    senderUserName = parts[1];
                    fileCount = 1;
                    fileNames = parts[2];
                    totalSize = Long.parseLong(parts[3]);
                    sb.append(fileNames);
                    println("單檔傳送：SenderUserName=" + senderUserName + ", fileNames=" + fileNames + ", totalSize="
                            + totalSize);
                } else if (parts[0].equals("isMulti")) {
                    senderUserName = parts[1];
                    folderName = parts[2];
                    fileCount = parts.length - 4;
                    totalSize = Long.parseLong(parts[parts.length - 1]);
                    for (int i = 3; i < parts.length - 1; i++) {
                        sb.append(parts[i]).append("\n");
                    }
                    println("多檔傳送：SenderUserName=" + senderUserName + ", folderName=" + folderName);
                } else {
                    System.err.println("無效的 handshake 類型： " + parts[0]);
                    continue;
                }
                // ask user to accept the file
                // build a scrollable text area for the file list
                StringBuilder listText = new StringBuilder();
                if (isSingle) {
                    listText.append(fileNames);
                } else {
                    for (int j = 3; j < parts.length - 1; j++) {
                        listText.append(parts[j]).append("\n");
                    }
                }
                String info = "Sender: " + senderUserName
                        + "\nFolder: " + folderName
                        + "\nTotal Size: " + totalSize + " bytes\n\nFiles:\n";
                JTextArea ta = new JTextArea(info + listText.toString());
                ta.setEditable(false);
                ta.setLineWrap(true);
                ta.setWrapStyleWord(true);

                // wrap it in a scroll pane
                JScrollPane pane = new JScrollPane(ta,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                pane.setPreferredSize(new Dimension(400, 200));

                // show the confirm dialog with the scroll pane as the message component
                int response = JOptionPane.showConfirmDialog(
                    null,
                    pane,
                    "檔案傳送 — 接收確認",
                    JOptionPane.YES_NO_OPTION
                );
                if (response != JOptionPane.YES_OPTION) {
                    try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                        dos.writeUTF("REJECT");
                        dos.flush();
                        continue;
                    } catch (IOException e) {
                        System.err.println("無法與 Sender 通訊：");
                        e.printStackTrace();
                    }
                    System.out.println("使用者拒絕接收檔案。");
                    continue;

                }

                // get output file path
                String outputFilePath = FolderSelector.selectFolder();
                if (outputFilePath == null) {
                    System.out.println("使用者取消選擇資料夾。");
                    try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                        dos.writeUTF("REJECT");
                        dos.flush();
                    } catch (IOException e) {
                        System.err.println("無法與 Sender 通訊：");
                        e.printStackTrace();
                    }
                    continue;
                }
                if (!isSingle) {
                    outputFilePath = outputFilePath + "\\" + folderName;
                    println("outputFilePath: " + outputFilePath);
                    File folder = new File(outputFilePath);
                    if (!folder.exists()) {
                        folder.mkdirs(); // Create the directory if it doesn't exist
                        println("已建立資料夾：" + folderName);
                    }
                }
                // send accept message to sender
                try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                    dos.writeUTF("ACK");
                    dos.flush();
                } catch (IOException e) {
                    System.err.println("無法與 Sender 通訊：");
                    e.printStackTrace();
                }
                final String outPutPath = outputFilePath;
                println("已接受檔案傳送。");

                // notify sender to start sending the file
                cb.onStart(totalSize); // 開始接收檔案
                SwingUtilities.invokeLater(() -> SendFileGUI.receiveFileProgress(0));
                println(fileCount + " 個檔案，總大小：" + totalSize + " bytes");
                for (int i = 0; i < fileCount; i++) {
                    try(Socket ctrlSock = serverSocket.accept();
                    DataInputStream  fileDis = new DataInputStream(ctrlSock.getInputStream()))
                    {
                        String[] pp = fileDis.readUTF().split("\\|");
                        final String fileName = pp[0];
                        long fileSize = Long.parseLong(pp[1]);
                        println("接收檔案：" + fileName + "，大小：" + fileSize + " bytes");
                        // notify sender to start sending the file
                        // try (DataOutputStream dos = new DataOutputStream(ctrlSock.getOutputStream())) {
                        //     dos.writeUTF("ACK");
                        //     dos.flush();
                        while(Receiver.start(serverSocket, outPutPath + "\\" + fileName, fileSize, cb) == false) {
                            // 等待所有 handler 完成
                            try {
                                Thread.sleep(1000); // 等待 10 mill second 後重試
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        // } catch (IOException e) {
                        //     System.err.println("無法與 Sender 通訊：");
                        //     socket.close();
                        //     dis.close();
                        //     e.printStackTrace();
                        // }

                    } catch (IOException e) {
                        socket.close();
                    }

                }
            } catch (IOException e) {
                System.err.println("無法連線到 Sender：");
                e.printStackTrace();
            }
        }
    }

}
