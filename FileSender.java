package AirShit;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.naming.ldap.SortKey;
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
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
            // 傳送 handshake 訊息
            dos.writeUTF(sb.toString());
        } catch (IOException e) {
            System.err.println("無法連線到 Receiver：");
            e.printStackTrace();
            return;
        }

        // wait for receiver to accept the file
        Socket socket = new Socket(host, port);
        int cnt = 0;
        while(receiveACK(socket) && cnt < 30) {
            Thread.sleep(10); // 等待 Receiver 準備好
            cnt++;
        }
        if(cnt >= 30) {
            System.err.println("Receiver 無法接收檔案，請稍後再試。");
            return;
        }
        // notify receiver to start receiving the file
        callback.onStart(totalSize);
        this.cb = callback;
        for(File f : files) {
            println("開始傳送檔案：" + f.getName() + "，大小：" + f.length() + " bytes");
            SendingFile = f;
            // notify receiver to start receiving the file
            try {
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                // 傳送檔案名稱與大小
                dos.writeUTF(f.getName()+"|"+f.length());
            } catch (IOException e) {
                System.err.println("無法連線到 Receiver：");
                e.printStackTrace();
                return;
            }
            // 等待 Receiver 確認接收檔案
            while(true) {
                if(receiveACK(socket)) {
                    println("Receiver 接受，開始傳送檔案。");
                    break;
                } else {
                    System.err.println("Receiver 無法接收檔案，請稍後再試。");
                    return;
                }
            }


            long fileLength = f.length();
            long baseChunkSize = Math.min(5*1024*1024*1024, fileLength) / threadCount;// 每個執行緒傳送的檔案大小
            int i = 0;
            while(fileLength > 0) {
                List<Thread> workers = new ArrayList<>();
                for (; i < threadCount; i++) {
                    long offset = i * baseChunkSize;
                    // 最後一塊撥給剩下的所有 byte
                    long chunkSize = (i == threadCount - 1)
                        ? Math.min(fileLength - offset, baseChunkSize)
                        : baseChunkSize;
        
                    Thread t = new Thread(new ChunkSender(offset, (int) chunkSize));
                    t.start();
                    workers.add(t);
                }
        
                // 等待所有執行緒結束
                for (Thread t : workers) {
                    t.join();
                }
                fileLength -= baseChunkSize * threadCount;
            }
            System.out.printf("檔案傳輸完成，總共傳送 %d bytes%n", totalSent.get());
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
                    dos.write(buffer, 0, read);
                    totalSent.addAndGet(read);
                    remaining -= read;
                    println("已傳送 " + read + " bytes");
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
