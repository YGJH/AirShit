package AirShit;
import java.io.*;
import java.net.Socket;
/**
 * SendFile: 將檔案分割成多段，並以多執行緒同時傳送給 Receiver。
 */
public class FileSender {
    private  String host;
    private  int port;
    private  String fatherDir; // 父資料夾
    // get Hardware Concurrent

    public FileSender(String host, int port , String fatherDir) { 
        this.host = host;
        this.port = port;
        this.fatherDir = fatherDir;
        System.out.println("fatherDir: " + fatherDir);
    }
    public void println (String str) {
        System.out.println(str);
    }
    public void sendFiles(String[] files , String SenderUserName , String folderName , TransferCallback callback) throws IOException, InterruptedException {

        // handshake
        StringBuilder sb = new StringBuilder();
        long totalSize = 0;
        int threadCount = Runtime.getRuntime().availableProcessors(); // 硬體執行緒數量
        boolean isSingleFile = files.length == 1;
        // System.out.println("folderName: " + fatherDir+"\\"+folderName+"\\"+files[0]);
        if(isSingleFile) {
            sb.append("isSingle|");
            sb.append(SenderUserName).append("|").append(new File(fatherDir+"\\"+folderName+"\\"+files[0]).getName());
            totalSize = new File(fatherDir+"\\"+folderName+"\\"+files[0]).length();
        } else {
            sb.append("isMulti|");
            sb.append(SenderUserName + "|" + folderName);
            for (String filePath : files) {
                File f = new File(fatherDir + "\\" + folderName +"\\"+ filePath);
                totalSize += f.length();
                sb.append("|").append(f.getName());
            }
        }
        sb.append("|").append(totalSize);
        long totalSize2 = totalSize;
        sb.append("|");
        if(totalSize > 1024*1024*1024) {
            totalSize2 = totalSize / (1024*1024*1024);
            sb.append(totalSize2 + "  GB");
        } else if(totalSize > 1024*1024) {
            totalSize2 = totalSize / (1024*1024);
            sb.append(totalSize2 + " MB");
        } else if(totalSize > 1024) {
            totalSize2 = totalSize / (1024);
            sb.append(totalSize2 + " KB");
        } else {
            sb.append(totalSize + " B");
        }
        sb.append("|"+(threadCount)); // 硬體執行緒數量
        // 連線到 Receiver
        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            // 傳送 handshake 訊息
            dos.writeUTF(sb.toString());
            dos.flush();
            println("傳送 handshake 訊息： " + sb.toString());
            // 等待 Receiver 確認接收檔案
            String response = dis.readUTF();
            
            if (response.startsWith("ACK")) {
                String thread = response.split("\\|")[1];
                int tmp = Integer.parseInt(thread); // 硬體執行緒數量
                threadCount = Math.min(threadCount , tmp); // 硬體執行緒數量
                println("Receiver 確認接收檔案。");
            } else {
                System.err.println("Receiver 無法接收檔案，請稍後再試。");
                return;
            }

        } catch (IOException e) {
            System.err.println("無法連線到 Receiver：");
            return;
        }

        callback.onStart(totalSize);
        System.out.println(files.length + " 個檔案需要傳送。");
        int cnt = files.length;
        for (String filePath : files) {
            // notify user
            File file = new File(fatherDir+"\\"+folderName+"\\"+filePath);
            String fileName = filePath;
            String fileSize = String.valueOf(file.length());
            try (Socket socket2 = new Socket(host, port);
                DataOutputStream dos = new DataOutputStream(socket2.getOutputStream());
                DataInputStream  dis = new DataInputStream(socket2.getInputStream())) {
                System.out.println("開始傳送檔案：" + fileName + " size: " + fileSize);
                // 1) send the file‑name|size header
                dos.writeUTF(fileName + "|" + fileSize);
                dos.flush();
        
                // 2) wait for ACK on the same socket
                String response = dis.readUTF();
                if (!"ACK".equals(response)) {
                    System.err.println("Receiver 無法接收檔案：" + fileName);
                    return;
                } else {
                    println("receiver 已開始接收檔案");
                }
            
                // 3) now kick off your SendFile/ChunkSender against socket2
                SendFile sendFile = new SendFile(
                    host, port, file,threadCount, callback);
                sendFile.start();

                response = dis.readUTF();
                println(response);
                if (!"ACK".equals(response)) {
                    System.err.println("Receiver 無法接收檔案：" + fileName);
                    callback.onError(new IOException("Receiver 無法接收檔案：" + fileName));
                    return;
                } else {
                    println("receiver 已完成接收檔案");
                }
                
            } catch (IOException | InterruptedException e) {
                callback.onError(e);
            }
        }
        System.out.println("所有檔案傳送完成。");
        callback.onComplete();
    }


}
