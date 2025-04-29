package AirShit;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;



public class Receiver {
    public static void println(String a) {
        System.out.println(a);
    }
    public static boolean start(ServerSocket serverSocket,
                                String outputFile,
                                long fileSize,
                                TransferCallback cb) throws IOException {

        // AtomicLong totalReceived = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Thread> handlers = new ArrayList<>();
        File out = new File(outputFile);
        long baseChunkSize = Math.min(fileSize, 5L*1024*1024) / Runtime.getRuntime().availableProcessors();
        // spawn one handler per chunk
        int chunkCount = Runtime.getRuntime().availableProcessors(); 
        for (int i = 0; i < chunkCount; i++) {
            pool.submit(() -> {
                try (
                  Socket sock = serverSocket.accept();
                  DataInputStream dis = new DataInputStream(sock.getInputStream());
                  RandomAccessFile raf = new RandomAccessFile(out, "rw")
                ) {
                    long offset = dis.readLong();
                    int  length = dis.readInt();

                    raf.seek(offset);
                    byte[] buf = new byte[8*1024];   // 8 KB 緩衝改小
                    int r, rem = length;
                    while (rem > 0 && (r = dis.read(buf, 0, Math.min(buf.length, rem))) > 0) {
                        raf.write(buf, 0, r);
                        // totalReceived.addAndGet(r);
                        rem -= r;
                        if (cb != null) cb.onProgress(r);
                    }
                    println("Chunk: " + offset + ", length: " + length);
                } catch (IOException e) {
                    System.err.println("Handler 發生錯誤：");
                    out.delete();
                    e.printStackTrace();
                }
            });
        }

        pool.shutdown();
        try {
            // 等所有 chunk 都完成
            if (!pool.awaitTermination(10000, TimeUnit.MINUTES)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        return true;//totalReceived.get() >= fileSize;
    }
}
