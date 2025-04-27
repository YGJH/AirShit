package AirShit;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;


import java.awt.Component;

/**
 * Receiver: 在指定埠口接收多個執行緒送過來的檔案分段，並寫入同一個檔案中。
 */
public class FileReceiver {

    public static int port;
    FileReceiver(int port) {
        this.port = port;
    }
    public static void println(String str) {
        System.out.println(str);
    }

    public static void start(TransferCallback cb) throws IOException { // 此port 是你本地的port

        // handshake
        while (true) {
            // listen for handshake
            ServerSocket serverSocket = new ServerSocket(port);
            boolean isSingle = false;
            String senderUserName = null;
            String fileName = null;
            String folderName = null;
            int fileCount = 0;
            long fileSize = 0;
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
                    fileName = parts[2];
                    fileSize = Long.parseLong(parts[3]);
                    sb.append(fileName);
                    println("單檔傳送：SenderUserName=" + senderUserName + ", fileName=" + fileName + ", fileSize="
                            + fileSize);
                } else if (parts[0].equals("isMulti")) {
                    senderUserName = parts[1];
                    folderName = parts[2];
                    fileCount = parts.length - 4;
                    fileSize = Long.parseLong(parts[parts.length - 1]);
                    for(int i = 3; i < parts.length - 1; i++) {
                        sb.append(parts[i]).append("|");
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
            int response = JOptionPane.showConfirmDialog(null, "是否接受檔案？" + " Sender: " + senderUserName + " 即將傳送的檔案: " + sb + " FolderName: " + folderName + " Total Size: " + fileSize , "檔案傳送", JOptionPane.YES_NO_OPTION);
            if (response != JOptionPane.YES_OPTION) {
                try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                    dos.writeUTF("REJECT");
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
            } else {
                File outputFile = new File(outputFilePath);
                println("接收單檔：" + outputFile.getAbsolutePath());
                if (!outputFile.exists()) {
                    outputFile.createNewFile();
                }
            }
            // send accept message to sender
            try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                dos.writeUTF("ACCEPT");
            } catch (IOException e) {
                System.err.println("無法與 Sender 通訊：");
                e.printStackTrace();
            }

            println("已接受檔案傳送。");
            // notify sender to start sending the file
            AtomicLong totalReceived = new AtomicLong(0);
            cb.onStart(fileSize);            // 開始接收檔案
            for (int i = 0; i < fileCount; i++) {
                // accept a new socket for this chunk
                Socket chunkSocket = serverSocket.accept();               // ← make a new local var
                DataInputStream dis = new DataInputStream(chunkSocket.getInputStream());
                String res = dis.readUTF();
                String[] p = res.split("\\|");
                File outputFile = new File(outputFilePath, p[0]);
                int tempfileSize = Integer.parseInt(p[1]);
                sendACK(chunkSocket);

                AtomicLong thisFileReceived = new AtomicLong(0);
                RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
                List<Thread> handlers = new ArrayList<>();

                while (thisFileReceived.get() < tempfileSize) {
                    Thread handler = new Thread(() -> {
                        try (
                            DataInputStream chunkDis = new DataInputStream(chunkSocket.getInputStream())
                        ) {
                            long offset = chunkDis.readLong();
                            int length = chunkDis.readInt();
                            raf.seek(offset);

                            byte[] buffer = new byte[8192];
                            int read, remaining = length;
                            while (remaining > 0
                                && (read = chunkDis.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                                raf.write(buffer, 0, read);
                                thisFileReceived.addAndGet(read);
                                cb.onProgress(thisFileReceived.get());
                                remaining -= read;
                            }
                        } catch (IOException e) {
                            System.err.println("Handler 發生錯誤：");
                            e.printStackTrace();
                        }
                    }, "chunk-handler-" + i);

                    handler.start();
                    handlers.add(handler);
                }

                // wait for all chunk‐handlers to finish
                for (Thread h:handlers) {
                    try {
                        h.join();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }

                raf.close();
                totalReceived.addAndGet(tempfileSize);
            }

            // （可加上條件退出並 handler.join()）
        }
    }

    

    private static void sendACK(Socket socket) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
            dos.writeUTF("ACK");
        } catch (IOException e) {
            System.err.println("無法與 Sender 通訊：");
            e.printStackTrace();
        }
    }

}
