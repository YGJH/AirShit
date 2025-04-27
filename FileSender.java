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
            if (!receiveACK(socket)) {
                System.err.println("Receiver 無法接收檔案，請稍後再試。");
                return;
            }
        

            new Thread(() -> {
                String name = file.getName();
                try (Socket sock = new Socket(host, port);
                     DataOutputStream dos = new DataOutputStream(
                         new BufferedOutputStream(sock.getOutputStream())
                     );
                     FileInputStream fis = new FileInputStream(file)) {

                    long total = file.length();

                    // send metadata
                    dos.writeUTF(name);
                    dos.writeLong(total);
                    dos.flush();
                    // send file content
                    byte[] buffer = new byte[8192];
                    long sent = 0;
                    int read;
                    while ((read = fis.read(buffer)) != -1) {
                        dos.write(buffer, 0, read);
                        sent += read;
                        callback.onProgress(sent);
                    }
                    dos.flush();
                } catch (Exception e) {}
            }, "sender-" + file.getName()).start();
        }
    }



    private boolean receiveACK(Socket socket) throws IOException {
        try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            String response = dis.readUTF();
            return response.equals("ACK");
        } catch (IOException e) {
            System.err.println("無法與 Receiver 通訊：");
            e.printStackTrace();
            return false;
        }
    }

    private class ChunkSender implements Runnable {
        private final long offset;
        private final int length;

        public ChunkSender(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }

        @Override
        public void run() {
            try (
                Socket socket = new Socket(host, port);
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                RandomAccessFile raf = new RandomAccessFile(SendingFile, "r")
            ) {
                // 先傳 offset 與 chunk 大小（header）
                dos.writeLong(offset);
                dos.writeInt(length);

                // 移動到 offset 並依序讀取、傳送
                raf.seek(offset);
                byte[] buffer = new byte[8192];
                int read, remaining = length;
                while (remaining > 0 && (read = raf.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                    println("已傳送 " + read + " bytes");
                    dos.write(buffer, 0, read);
                    totalSent.addAndGet(read);
                    remaining -= read;
                    cb.onProgress(totalSent.get());
                }
                System.out.printf("已傳送分段：offset=%d, length=%d%n", offset, length);
            } catch (IOException e) {
                System.err.println("ChunkSender 發生錯誤：");
                e.printStackTrace();
            }
        }
    }


}
