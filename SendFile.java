package AirShit;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SendFile: 將檔案分割成多段，並以多執行緒同時傳送給 Receiver。
 */
public class SendFile {
    private final String host;
    private final int port;
    private final File file;
    private final int threadCount;
    private final AtomicLong totalSent = new AtomicLong(0);
    private final TransferCallback callback;
    public SendFile(String host, int port, File file, int threadCount , TransferCallback callback) {
        this.callback = callback;
        this.host = host;
        this.port = port;
        this.file = file;
        this.threadCount = threadCount;
    }

    public void start() throws IOException, InterruptedException {
        long fileLength = file.length();
        long baseChunkSize = Math.min(fileLength, 5L*1024*1024*1024) / threadCount;
        List<Thread> workers = new ArrayList<>();
        long workerCount = fileLength / baseChunkSize;
        for (int i = 0; i < workerCount; i++) {
            long offset = i * baseChunkSize;
            // 最後一塊撥給剩下的所有 byte
            long chunkSize = (i == workerCount - 1)
                ? fileLength - offset
                : baseChunkSize;

            Thread t = new Thread(new ChunkSender(offset, (int) chunkSize));
            t.start();
            workers.add(t);
        }

        // 等待所有執行緒結束
        for (Thread t : workers) {
            t.join();
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
                byte[] buffer = new byte[8 * 1024 * 1024]; // 8 MB
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
        }
    }

    // public static void main(String[] args) throws Exception {
    //     if (args.length != 4) {
    //         System.err.println("Usage: java SendFile <host> <port> <file-path> <threads>");
    //         System.exit(1);
    //     }
    //     String host = args[0];
    //     int port = Integer.parseInt(args[1]);
    //     String filePath = args[2];
    //     int threads = Integer.parseInt(args[3]);

    //     new SendFile(host, port, filePath, threads).start();
    // }
}
