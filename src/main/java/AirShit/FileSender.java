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
            for(File f : file.listFiles()) {
                total_files_size += f.length();
                sb.append(f.getName() + "@");
            }
            sb.append(THREADS_STR+"@"+Long.toString(total_files_size));
            isCompress = true;
            System.out.println(file.getAbsolutePath() + ".tar.lz4");
            archFile = LZ4FileCompressor.compressFolderToTarLz4(file.getAbsolutePath() , file.getAbsolutePath() + ".tar.lz4");
            
        } else {
            archFile = file.getAbsolutePath();
            total_files_size = file.length();
            sb.append(file.getName() + "@" + THREADS_STR + "@" + Long.toString(total_files_size));
        }
        
        // send handshake
        boolean handshakeAccepted = false;
        try (Socket socket = new Socket(host, port);
        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
        DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_SECONDS * 1000); // 設定讀取超時
            dos.writeUTF(sb.toString());
            dos.flush();
            String handshakeString = sb.toString();
            int retries = 0;

            boolean sentSuccess = false;
            while (!handshakeAccepted && retries < MAX_HANDSHAKE_RETRIES) {
                try {
                    LogPanel.log("傳送握手訊息: " + handshakeString);
                    dos.writeUTF(handshakeString);
                    dos.flush();

                    String response = dis.readUTF(); // 這裡可能會拋出 SocketTimeoutException
                    LogPanel.log("收到回應: " + response);
                    if (response.startsWith("ACK")) {
                        sentSuccess = true;
                    } else {
                        LogPanel.log("收到無效的握手回應: " + response);
                        // 視為超時或錯誤，將會重試
                    }
                } catch (SocketTimeoutException e) {
                    LogPanel.log("握手超時 (讀取回應超時)。準備重試...");
                    // handshakeAccepted 仍然是 false，會進入下一次重試
                } catch (IOException e) {
                    LogPanel.log("握手時發生 IO 錯誤: " + e.getMessage());
                    // e.printStackTrace(); // 可選
                    // handshakeAccepted 仍然是 false，會進入下一次重試
                    if (retries >= MAX_HANDSHAKE_RETRIES -1 && callback != null) { // 如果是最後一次重試失敗
                        callback.onError(new IOException("握手失敗，已達最大重試次數", e));
                    }
                    new File(archFile).delete();
                } finally {
                    // 確保在每次嘗試後關閉資源
                    if (dos != null) try { dos.close(); } catch (IOException e) { /* ignore */ }
                    if (dis != null) try { dis.close(); } catch (IOException e) { /* ignore */ }
                    if (socket != null) try { socket.close(); } catch (IOException e) { /* ignore */ }
                
                }

                if (!sentSuccess) {
                    retries++;
                    if (retries < MAX_HANDSHAKE_RETRIES) {
                        LogPanel.log("等待 " + HANDSHAKE_TIMEOUT_SECONDS / 2 + " 秒後重試...");
                        TimeUnit.SECONDS.sleep(HANDSHAKE_TIMEOUT_SECONDS / 2); // 重試前稍作等待
                    }
                }
            } // end while for retries


            String response = dis.readUTF(); // 這裡可能會拋出 SocketTimeoutException
            LogPanel.log("收到回應: " + response);
            if (response.startsWith("OK@")) {
                handshakeAccepted = true;
                try {
                    int t = Integer.parseInt(response.split("@")[1]);
                    threadCount = Math.min(ITHREADS, t);
                    LogPanel.log("握手成功。協商執行緒數: " + threadCount);
                    dos.writeUTF("ACK");
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    LogPanel.log("錯誤：解析伺服器回應中的執行緒數失敗 - " + response);
                    handshakeAccepted = false; // 解析失敗，視為握手失敗
                    if (callback != null) callback.onError(new IOException("無效的回應格式: " + response, e));
                    // 不需要 return，會進入下一次重試或結束迴圈
                }
            } else if (response.equals("REJECT")) {
                LogPanel.log("接收端拒絕連線。");
                if (callback != null) callback.onError(new IOException("接收端拒絕握手"));
                return; // 直接返回，不再重試
            }

            File sentFile = new File(archFile);
            if(handshakeAccepted) {
                dos.writeUTF(sentFile.getName());
                String rs = dis.readUTF();
                if(rs.equals("ACK")) {

                    callback.onStart(total_files_size);
                    boolean isFine = true;
                    try { 
                        sender = new SendFile(this.host , this.port , sentFile , threadCount , callback); 
                    }
                    catch (Exception e) {
                        isFine = false;
                        if(isCompress)
                            sentFile.delete();
                        callback.onError(e);
                    }
                    if(isFine) {
                        callback.onComplete();
                    }
                    if(isCompress)
                            sentFile.delete();
                } else {
                    callback.onError(new Exception("Error"));
                    if(isCompress) {
                        sentFile.delete();
                    }
                }
            } else {
                if(isCompress)
                    sentFile.delete();
            }
        } catch (Exception e) {
            if(isCompress) {
                new File(archFile).delete();
            }
            callback.onError(e);
            return;
        }
    }
    // ate void dfs(File now) {
    // totalSize = 0;
    // tmp = 0 , curFileSize = 0;
    // cnt = 0 ;
    // ng currentFolderName = now.getParent();
    // = "";
    // now.listFile()) {
    // tory()) {
    //
    // fs(f , cc);
    // = Compress_file_size && cc > 1) {
    // ) + ".tar.lz4";
    // essor.compressFolderToTarLz4(f.getName() , S);
    //
    //
    //
    // = f.length();
    //
    // tName();
    //
    // d(S);
    // es[fileCount++] = f.getAbsolutePath();
    // curFileSize;
    // curFileSize > Compress_file_size && cnt > 1) {
    // LZ4FileCompressor.compressFolderToTarLz4(f.getParent() , ())
    //

}