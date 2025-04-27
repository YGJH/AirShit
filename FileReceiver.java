package AirShit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.xml.crypto.Data;

import AirShit.Receiver;

import java.awt.Component;

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
        while (true) {
            // listen for handshake
            ServerSocket serverSocket = new ServerSocket(port);
            boolean isSingle = false;
            String senderUserName = null;
            String fileNames = null;
            String folderName = null;
            int fileCount = 0;
            long totalSize = 0;
            StringBuilder sb = new StringBuilder();
            Socket socket = serverSocket.accept();
            try {
                DataInputStream dis = new DataInputStream(socket.getInputStream());
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
                    for(int i = 3; i < parts.length - 1; i++) {
                        sb.append(parts[i]).append("\n");
                    }
                    println("多檔傳送：SenderUserName=" + senderUserName + ", folderName=" + folderName);
                } else {
                    System.err.println("無效的 handshake 類型： " + parts[0]);
                    continue;
                }
            } catch (IOException e) {
                System.err.println("無法連線到 Sender：");
                e.printStackTrace();
            }
            // ask user to accept the file
            int response = JOptionPane.showConfirmDialog(null, "是否接受檔案？" + " Sender: " + senderUserName + " 即將傳送的檔案: " + sb + " FolderName: " + folderName + " Total Size: " + totalSize , "檔案傳送", JOptionPane.YES_NO_OPTION);
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
            cb.onStart(totalSize);

            // notify sender to start sending the file
            AtomicLong totalReceived = new AtomicLong(0);
            cb.onStart(totalSize); // 開始接收檔案
            for(int i = 0 ; i < fileCount; i++) {
                try {
                    Socket socket2 = serverSocket.accept(); 
                    DataInputStream dis = new DataInputStream(socket2.getInputStream());
                    String[] parts = dis.readUTF().split("\\|");
                    final String fileName = parts[0];
                    long fileSize = Long.parseLong(parts[1]);
                    println("接收檔案：" + fileName + "，大小：" + fileSize + " bytes");
                    // notify sender to start sending the file
                    try (DataOutputStream dos = new DataOutputStream(socket2.getOutputStream())) {
                        dos.writeUTF("ACK");
                        dos.flush();
                    } catch (IOException e) {
                        System.err.println("無法與 Sender 通訊：");
                        e.printStackTrace();
                    }

                }
                catch (IOException e) {
                }
                // 接收檔案
                serverSocket.close();
                try {
                    Receiver.start(port, outPutPath + "\\" + fileNames, cb);
                } catch (IOException e) {
                    System.err.println("無法接收檔案：");
                    e.printStackTrace();
                }


            }
        }     
    }

}
