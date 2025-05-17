package AirShit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
public class Receiver {
    private final ServerSocket serverSocket;
    public Receiver(ServerSocket ss) {
        this.serverSocket = ss;
    }
    public boolean start(String outputFile,
                         long fileLength,
                         int threadCount,
                         TransferCallback cb) throws IOException {
        File out = new File(outputFile);
        // 计算要分多少 worker（chunk）就不展开了…
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        for (int w = 0; w < workerCount; w++) {
            for (int t = 0; t < threadCount; t++) {
                // accept data‐connection
                Socket dataSock = serverSocket.accept();
                pool.submit(new ChunkReceiver(dataSock, out, cb));
            }
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);
        return true;
    }

    private static class ChunkReceiver implements Runnable {
        private final Socket socket;
        private final File   out;
        private final TransferCallback cb;

        public ChunkReceiver(Socket socket, File out, TransferCallback cb) {
            this.socket = socket;
            this.out    = out;
            this.cb     = cb;
        }

        @Override
        public void run() {
            try (
              DataInputStream dis = new DataInputStream(socket.getInputStream());
              RandomAccessFile raf = new RandomAccessFile(out, "rw")
            ) {
                // 1) 先从 client 端 header 读出实际 offset/length
                long offset = dis.readLong();
                int length  = dis.readInt();
                raf.seek(offset);              // 绝对安全，不会负数

                // 2) 再按 length 读数据
                byte[] buf = new byte[8*1024];
                int rem = length, r;
                while (rem > 0 && (r = dis.read(buf, 0, Math.min(buf.length, rem))) > 0) {
                    raf.write(buf, 0, r);
                    rem -= r;
                    if (cb != null) cb.onProgress(r);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
