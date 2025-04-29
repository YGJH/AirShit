package AirShit;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;



public class Receiver {
    public static void println(String a) {
        System.out.println(a);
    }
    public static boolean start(ServerSocket serverSocket , String outputFile , long fileSize , TransferCallback cb) throws IOException {

        println("開始接收: " + (outputFile).toString());
        // 使用 RandomAccessFile 以便於多執行緒寫入不同 offset
        AtomicLong totalReceived = new AtomicLong(0);
        List<Thread> handlers = new ArrayList<>();
        final File out = new File(outputFile);

        while (totalReceived.get() < fileSize) {
            Thread handler = new Thread(() -> {
                try (
                    Socket sock = serverSocket.accept();
                    DataInputStream dis = new DataInputStream(sock.getInputStream());
                    RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
                ) {
                    int segments = dis.readInt();
                    for (int i = 0; i < segments; i++) {
                      long offset = dis.readLong();
                      int len    = dis.readInt();
                      byte[] buf = new byte[8192];
                      int r, rem = len;
                      raf.seek(offset);
                      while (rem > 0 && (r = dis.read(buf,0,Math.min(buf.length,rem)))>0) {
                        raf.write(buf,0,r);
                        rem -= r;
                        if (cb!=null) cb.onProgress(r);
                      }
                    }
                } catch (IOException e) {
                    System.err.println("Handler 發生錯誤：");
                    out.delete();
                    e.printStackTrace();
                }
            });
            handler.start();
            handlers.add(handler);
        }
        for(Thread t : handlers) {
            try {
                t.join(); // 等待所有 handler 完成
            } catch (InterruptedException e) {
                out.delete();
                e.printStackTrace();
            }
        }

        // 等待所有 handler 完成
        return true; // 這裡可以根據實際情況返回成功或失敗的狀態

        // （可加上條件退出並 handler.join()）
    }
}
