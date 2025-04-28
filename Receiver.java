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
    public static void start(ServerSocket serverSocket , String outputFile , long fileSize , TransferCallback cb) throws IOException {

        println("開始接收: " + (outputFile).toString());
        // 使用 RandomAccessFile 以便於多執行緒寫入不同 offset
        AtomicLong totalReceived = new AtomicLong(0);
        List<Thread> handlers = new ArrayList<>();
        final File out = new File(outputFile);

        while (totalReceived.get() < fileSize) {
            Thread handler = new Thread(() -> {
                try (
                    Socket socket = serverSocket.accept();
                    RandomAccessFile raf = new RandomAccessFile(out, "rw");
                    DataInputStream dis = new DataInputStream(socket.getInputStream())
                ) {
                    // 讀取 offset 與 chunk 大小
                    long offset = dis.readLong();
                    int length = dis.readInt();

                    // 寫入資料
                    raf.seek(offset);
                    byte[] buffer = new byte[8 * 1024 * 1024];
                    int read, remaining = length;
                    while (remaining > 0 && (read = dis.read(buffer, 0, Math.min(buffer.length, remaining))) != -1 && remaining > 0) {
                        raf.write(buffer, 0, read);
                        totalReceived.addAndGet(read);
                        remaining -= read;
                        cb.onProgress(read);
                    }
                    System.out.printf("接收分段：offset=%d, length=%d | 總共已接收：%d bytes%n",
                                      offset, length, totalReceived.get());
                } catch (IOException e) {
                    System.err.println("Handler 發生錯誤：");
                    out.delete();
                    e.printStackTrace();
                }
            });
            handler.start();
            handlers.add(handler);
            for(Thread t : handlers) {
                try {
                    t.join(); // 等待所有 handler 完成
                } catch (InterruptedException e) {
                    out.delete();
                    e.printStackTrace();
                }
            }
        }

        

        // （可加上條件退出並 handler.join()）
    }
}
