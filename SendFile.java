package AirShit;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SendFile: 改成使用 UDP 傳輸檔案分段
 */
public class SendFile {
    private final String host;
    private final int port;
    private final File file;
    private final TransferCallback callback;

    public SendFile(String host, int port, File file, TransferCallback callback) {
        this.host = host;
        this.port = port;
        this.file = file;
        this.callback = callback;
    }

    public void start() throws IOException, InterruptedException {
        long fileLength    = file.length();
        int  cores         = Runtime.getRuntime().availableProcessors();
        long baseChunkSize = Math.min(fileLength, 5L * 1024 * 1024) / cores;
        int  chunkCount    = (int)(fileLength / baseChunkSize);

        ExecutorService pool = Executors.newFixedThreadPool(cores);
        for (int i = 0; i < chunkCount; i++) {
            long offset = i * baseChunkSize;
            int  size   = (i == chunkCount - 1)
                         ? (int)(fileLength - offset)
                         : (int)baseChunkSize;
            pool.submit(new ChunkSender(offset, size));
        }

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);
    }

    private class ChunkSender implements Runnable {
        private final long offset;
        private final int  length;

        public ChunkSender(long offset, int length) {
            this.offset = offset;
            this.length = length;
        }

        @Override
        public void run() {
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                // 1) 讀取整個 chunk
                byte[] data = new byte[length];
                raf.seek(offset);
                raf.readFully(data);

                // 2) 建立 UDP socket 並打包 [offset(8)][length(4)][data]
                DatagramSocket ds = new DatagramSocket();
                ByteBuffer bb = ByteBuffer.allocate(8 + 4 + length);
                bb.putLong(offset);
                bb.putInt(length);
                bb.put(data);
                byte[] packetBytes = bb.array();

                // 3) 傳送到 receiver
                InetAddress addr = InetAddress.getByName(host);
                DatagramPacket packet = new DatagramPacket(
                    packetBytes, packetBytes.length, addr, port);
                ds.send(packet);
                ds.close();

                // 4) 回報進度
                callback.onProgress(length);

            } catch (IOException e) {
                callback.onError(e);
                System.err.println("ChunkSender 發生錯誤：");
                e.printStackTrace();
            }
        }
    }
}
