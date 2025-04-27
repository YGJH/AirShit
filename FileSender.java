package AirShit;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.xml.crypto.Data;

/**
 * SendFile: 將檔案分割成多段，並以多執行緒同時傳送給 Receiver。
 */
public class FileSender {
    private File SendingFile;
    private TransferCallback cb;
    private  String host;
    private  int port;
    // get Hardware Concurrent
    private final int threadCount = Math.max(Runtime.getRuntime().availableProcessors() - 2, 1);
    private  AtomicLong totalSent = new AtomicLong(0);

    public FileSender(String host, int port) { // 此port 是對方的port跟host
        this.host = host;
        this.port = port;
    }
    public void println (String str) {
        System.out.println(str);
    }
    public void sendFiles(File[] files , String SenderUserName , String folderName , TransferCallback callback) throws IOException, InterruptedException {

        // handshake
        StringBuilder sb = new StringBuilder();
        long totalSize = 0;
        boolean isSingleFile = files.length == 1;
        if(isSingleFile) {
            sb.append("isSingle|");
            sb.append(SenderUserName).append("|").append(files[0].getName()).append("|").append(files[0].length());
        } else {
            sb.append("isMulti|");
            sb.append(SenderUserName + "|" + folderName);
            for (File f : files) {
                totalSize += f.length();
                sb.append("|").append(f.getName());
            }
            sb.append("|").append(totalSize);
        }

        // 連線到 Receiver
        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            // 傳送 handshake 訊息
            dos.writeUTF(sb.toString());
            println("傳送 handshake 訊息： " + sb.toString());
            // 等待 Receiver 確認接收檔案
            String response = dis.readUTF();
            if (response.equals("ACK")) {
                println("Receiver 確認接收檔案。");
            } else {
                System.err.println("Receiver 無法接收檔案，請稍後再試。");
                return;
            }

        } catch (IOException e) {
            System.err.println("無法連線到 Receiver：");
            e.printStackTrace();
            return;
        }

        callback.onStart(totalSize);
        this.cb = callback;
        for (File file : files) {
            // notify user
            String fileName = file.getName();
            String fileSize = String.valueOf(file.length());
            Socket socket = new Socket(host, port);
            try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                // send file name and size
                dos.writeUTF(fileName+"\\|"+fileSize);
                dos.flush();
            } catch (IOException e) {}

            // wait for ACK
            if (!Main.receiveACK(socket)) {
                System.err.println("Receiver 無法接收檔案，請稍後再試。");
                return;
            }

            // send file
            SendFile sendFile = new SendFile(host, port, file.getAbsolutePath(), threadCount , new TransferCallback() {
                @Override
                public void onStart(long totalSize) {
                    // do nothing
                }

                @Override
                public void onProgress(long bytesTransferred) {
                    totalSent.addAndGet(bytesTransferred);
                    cb.onProgress(totalSent.get());
                }
                @Override
                public void onError(Exception e) {
                    cb.onError(e);
                }
            });
        
            sendFile.start();
        }
    }



}
