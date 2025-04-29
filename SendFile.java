package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * SendFile: 將檔案分割成多段，並以多執行緒同時傳送給 Receiver。
 */
public class SendFile {
    private final String host;
    private final int port;
    private final File file;
    private final AtomicLong totalSent = new AtomicLong(0);
    private final TransferCallback callback;

    public SendFile(String host, int port, File file, TransferCallback callback) {
        this.callback = callback;
        this.host = host;
        this.port = port;
        this.file = file;
    }

    public void start() throws IOException, InterruptedException {
        long fileLength    = file.length();
        long baseChunkSize = Math.min(fileLength, 5L * 1024 * 1024) / threadCount;
        long workerCount   = fileLength / baseChunkSize;

        // 建立固定大小 ThreadPool
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        System.out.printf("worker: %d, poolSize=%d%n", workerCount, threadCount);

        // submit 每個 chunk 處理
        for (int i = 0; i < workerCount; i++) {
            long offset = i * baseChunkSize;
            long chunkSize = (i == workerCount - 1)
                ? fileLength - offset
                : baseChunkSize;
            pool.submit(new ChunkSender(offset, (int) chunkSize));
        }

        // 關閉 pool，並等待所有任務完成
        pool.shutdown();
        if (!pool.awaitTermination(10, TimeUnit.MINUTES)) {
            pool.shutdownNow();
        }

        System.out.printf("檔案傳輸完成，總共傳送 %d bytes%n", totalSent.get());
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
            try {
                try (
                    Socket socket = new Socket(host, port);
                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                    RandomAccessFile raf = new RandomAccessFile(file, "r")
                ) {
                    // 先傳 offset 與 chunk 大小（header）
                    dos.writeLong(offset);
                    dos.writeInt(length);
                    dos.flush();

                // 移動到 offset 並依序讀取、傳送
                raf.seek(offset);
                byte[] buffer = new byte[8 * 1024]; // 8 kB
                int read, remaining = length;
                while (remaining > 0 && (read = raf.read(buffer, 0, Math.min(buffer.length, remaining))) != -1 && remaining > 0) {
                    dos.write(buffer, 0, read);
                    totalSent.addAndGet(read);
                    remaining -= read;
                    callback.onProgress(read);
                }
                System.out.printf("已傳送分段：offset=%d, length=%d%n", offset, length);
                } catch (IOException e) {
                    System.err.println("ChunkSender 發生錯誤：");
                    e.printStackTrace();
                }
            } catch (Exception e) {
                System.err.println("ChunkSender 發生錯誤：");
                e.printStackTrace();
            }
        }
    }

        // public static void main(String[] args) throws Exception {
        // if (args.length != 4) {
        // System.err.println("Usage: java SendFile <host> <port> <file-path>
        // <threads>");
        // System.exit(1);
        // }
        // String host = args[0];
        // int port = Integer.parseInt(args[1]);
        // String filePath = args[2];
        // int threads = Integer.parseInt(args[3]);

        // new SendFile(host, port, filePath, threads).start();
        // }
}
