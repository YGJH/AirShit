package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import AirShit.ui.*;
// import src.main.java.AirShit.ui.LogPanel;
import AirShit.ui.LogPanel;

/**
 * FileSender: Handles sending files, including potential compression.
 */
public class FileSender {
    private String host;
    private int port;
    private int Compress_file_size = 5 * 1024;
    private int Single_thread = 1 * 1024 * 1024;
    private StringBuilder sb;
    private long total_files_size = 0;
    private File[] pSendFiles;
    private int filesCount;
    private int threadCount = 0;
    private SendFile sender;
    private final int ITHREADS = Runtime.getRuntime().availableProcessors();

    private final String THREADS_STR = Integer.toString(ITHREADS);


    private static final int HANDSHAKE_TIMEOUT_SECONDS = 10; // 握手超時時間 (秒)
    private static final int MAX_HANDSHAKE_RETRIES = 3; // 最大重試次數



    public FileSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static void println(String str) {
        // System.out.println(str); // Replaced by LogPanel
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    public void sendFiles(File file , String senderUserName, TransferCallback callback) throws IOException, InterruptedException {
        if(file == null) {
            return;
        }
        total_files_size = 0;
        sb = new StringBuilder();
        String archFile = "";
        
        // add handshake information
        boolean isCompress = false;
        sb.append(senderUserName+"@");
        if(file.isDirectory()) {
            isCompress = true;
            System.out.println(file.getAbsolutePath() + ".tar.lz4");
            archFile = LZ4FileCompressor.compressFolderToTarLz4(file.getAbsolutePath() , file.getAbsolutePath() + ".tar.lz4");
            total_files_size = new File(archFile).length();
            sb.append(archFile+"@"+THREADS_STR+"@"+Long.toString(total_files_size));
        } else {
            archFile = file.getAbsolutePath();
            total_files_size = file.length();
            sb.append(file.getName() + "@" + THREADS_STR + "@" + Long.toString(total_files_size));
        }
        
        // send handshake
        try (Socket socket = new Socket(host, port);
        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
        DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000); // 設定讀取超時
            dos.writeUTF(sb.toString());
            dos.flush();
            String handshakeString = sb.toString();
            int retries = 0;
            
            boolean sentSuccess = false;
            while (retries < MAX_HANDSHAKE_RETRIES) {
                
                try {
                    LogPanel.log("傳送握手訊息: " + handshakeString);
                    dos.writeUTF(handshakeString);
                    dos.flush();

                    String response = dis.readUTF(); // 這裡可能會拋出 SocketTimeoutException
                    System.out.println("收到回應: " + response);
                    if (response.startsWith("ACK")) {
                        sentSuccess = true;
                        break;
                    } else {
                        System.out.println("收到無效的握手回應: " + response);
                        // 視為超時或錯誤，將會重試
                    }
                } catch (SocketTimeoutException e) {
                    System.out.println("握手超時 (讀取回應超時)。準備重試...");
                    // handshakeAccepted 仍然是 false，會進入下一次重試
                } catch (IOException e) {
                    System.out.println("握手時發生 IO 錯誤: " + e.getMessage());
                    // e.printStackTrace(); // 可選
                    // handshakeAccepted 仍然是 false，會進入下一次重試
                    if (retries >= MAX_HANDSHAKE_RETRIES -1 && callback != null) { // 如果是最後一次重試失敗
                        callback.onError(new IOException("握手失敗，已達最大重試次數", e));
                    }
                    new File(archFile).delete();
                }

                if (!sentSuccess) {
                    retries++;
                    if (retries < MAX_HANDSHAKE_RETRIES) {
                        System.out.println("等待 " + HANDSHAKE_TIMEOUT_SECONDS / 2 + " 秒後重試...");
                        TimeUnit.SECONDS.sleep(HANDSHAKE_TIMEOUT_SECONDS / 2); // 重試前稍作等待
                    }
                }
            } // end while for retries

            String receiverDecision = dis.readUTF(); // 讀取 "OK@threads" 或 "REJECT"
            System.out.println("接收端決定: " + receiverDecision);

            boolean handshakeAccepted = false;
            int negotiatedThreadCount = 1; // Default

            if (receiverDecision.startsWith("OK@")) {
                try {
                    int t = Integer.parseInt(receiverDecision.split("@")[1]);
                    negotiatedThreadCount = Math.min(ITHREADS, t); // ITHREADS 是 FileSender 的常數
                    LogPanel.log("握手成功。協商執行緒數: " + negotiatedThreadCount);
                    handshakeAccepted = true;
                    // 回覆 ACK 給接收端的 "OK@"
                    dos.writeUTF("ACK");
                    dos.flush();
                    LogPanel.log("已傳送 ACK 給接收端的 OK@。");

                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    LogPanel.log("錯誤：解析伺服器回應中的執行緒數失敗 - " + receiverDecision);
                    if (callback != null) callback.onError(new IOException("無效的回應格式: " + receiverDecision, e));
                    // 這裡可以選擇關閉 socket 或 return
                    socket.close(); // 主動關閉
                    return;
                }
            } else if (receiverDecision.equals("REJECT")) {
                LogPanel.log("接收端拒絕連線。");
                if (callback != null) callback.onError(new IOException("接收端拒絕握手"));
                // socket 會在 try-with-resources 結束時關閉
                return; // 直接返回，不再重試
            } else {
                LogPanel.log("錯誤：來自接收端的未知決定: " + receiverDecision);
                if (callback != null) callback.onError(new IOException("未知的接收端決定: " + receiverDecision));
                socket.close(); // 主動關閉
                return;
            }

            File sentFile = new File(archFile);
            
            System.out.println(sentFile.getName());
            if(handshakeAccepted) {
                System.out.println("gonna sent to: " + sentFile.getName());
                dos.writeUTF(sentFile.getName());
                String rs = dis.readUTF();
                if(rs.equals("ACK")) {
                    callback.onStart(total_files_size);
                    sender = new SendFile(this.host , this.port , sentFile , negotiatedThreadCount , callback); 
                    Thread senderThread = new Thread(() -> {
                        boolean isFine = true;
                        try {
                            sender.start(); // This will now run in a new thread
                        } catch (Exception e) {
                            LogPanel.log("IOException in SendFile thread: " + e.getMessage());
                            isFine = false;
                            if (callback != null) {
                                callback.onError(e);
                            }
                            // Optionally, handle cleanup if isCompress and sentFile needs deletion
                        }
                        if(!isFine) {
                            sentFile.delete();
                        } else {
                            callback.onComplete();
                        }
                    });
                    senderThread.setName("FileSender-SendFile-Thread"); // Good practice to name threads
                    senderThread.run(); // Correct way to start a new thread
                    if(isCompress) {
                        sentFile.delete();
                    }

                }
            } else {
                if(isCompress) {
                    sentFile.delete();
                }
            }

        } catch(Exception e) {
            callback.onError(e);
            if(isCompress)
                new File(archFile).delete();
            return;
        }
    }
}