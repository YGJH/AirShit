package AirShit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
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
            long fileSize, int threadCount,
            TransferCallback cb) throws IOException {
        File out = new File(outputFile);
        AtomicLong totalReceived = new AtomicLong();

        // 1) 固定 chunk 大小 5MB
        long chunkSize   = 5L * 1024 * 1024;
        // 2) ceil 分割成多少 chunk
        long workerCount = (fileSize + chunkSize - 1) / chunkSize;

        // 3) 建一个固定 threadCount 的 pool
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < workerCount; i++) {
            pool.submit(() -> {
                try (
                    Socket sock = serverSocket.accept();
                    DataInputStream dis = new DataInputStream(sock.getInputStream());
                    RandomAccessFile raf = new RandomAccessFile(out, "rw")
                ) {
                    // 先讀 ChunkSender 寄來的 header
                    long offset = dis.readLong();
                    int  length = dis.readInt();
                    raf.seek(offset);
                    byte[] buf = new byte[8 * 1024];
                    int r, rem = length;
                    while (rem > 0 && (r = dis.read(buf, 0, Math.min(buf.length, rem))) > 0) {
                        raf.write(buf, 0, r);
                        totalReceived.addAndGet(r);
                        rem -= r;
                        if (cb != null) cb.onProgress(r);
                    }
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

        return totalReceived.get() >= fileSize;
    }
}
