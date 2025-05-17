package AirShit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
public class Receiver {
    private ServerSocket serverSocket;
    
    public static void println(String a) {
        System.out.println(a);
    }
    public Receiver(ServerSocket serverSocket) throws IOException {
        this.serverSocket = serverSocket;
        System.out.println("Receiver: " + serverSocket.getLocalPort());
    }
    public boolean start(
            String outputFile,
            long fileLength, int threadCount,
            TransferCallback cb) throws IOException {
        File out = new File(outputFile);

        long baseChunkSize = Math.min(fileLength, 5L * 1024 * 1024 * 1024);
        long workerCount = (long)Math.ceil((double)fileLength / (double)baseChunkSize);
        int chunkSize = (int)Math.ceil((double)baseChunkSize / (double)threadCount);

        // 3) 建一个固定 threadCount 的 pool
        long alreadySubbmitted = 0;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < workerCount; i++) {
            long processing = Math.min(baseChunkSize, fileLength - alreadySubbmitted);
            for (int j = 0; j < threadCount; j++) {
                long offset = j * chunkSize + alreadySubbmitted;
                int tempChunkSize = (j == threadCount - 1) ? (int)(processing - offset) : (int)chunkSize;
                pool.submit(new ChunkReceiver(offset, tempChunkSize, out, cb));
            }
            alreadySubbmitted += processing;
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

        return true;
    }

    private class ChunkReceiver implements Runnable {
        private final long offset;
        private final int length;
        private final File out;
        private final TransferCallback cb;
        public ChunkReceiver(long offset, int length, File out, TransferCallback cb) {
            this.offset = offset;
            this.length = length;
            this.out = out;
            this.cb = cb;
        }

        @Override
        public void run() {
            try (
                    Socket sock = serverSocket.accept();
                    DataInputStream dis = new DataInputStream(sock.getInputStream());
                    RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                // 先讀 ChunkSender 寄來的 header
                raf.seek(offset);
                byte[] buf = new byte[8 * 1024];
                int rem = length;
                int r = 0;
                while (rem > 0 && (r = dis.read(buf, 0, Math.min(buf.length, rem))) > 0) {
                    raf.write(buf, 0, r);
                    rem -= r;
                    if (cb != null)
                        cb.onProgress(r);
                }
            } catch (IOException e) {
                System.err.println("Handler 發生錯誤：");
                out.delete();
            }
        }
    }

}
