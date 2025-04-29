package AirShit;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class Receiver {

    /**
     * 使用 UDP + 多執行緒接收分段並寫入同一個檔案
     * @param port       接收資料的 UDP 埠
     * @param outputFile 最終輸出檔案路徑
     * @param fileSize   預期總大小
     * @param cb         傳輸回呼
     */
    public static boolean startUDP(int port,
                                   String outputFile,
                                   long fileSize,
                                   TransferCallback cb) throws IOException {
        // 1) 建立接收 socket
        DatagramSocket ds = new DatagramSocket(port);

        // 2) 準備輸出檔與併發寫入設備
        File outFile = new File(outputFile);
        RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
        AtomicLong totalReceived = new AtomicLong(0);

        // 3) 建立執行緒池
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(cores);

        // 4) 準備 buffer (8 bytes offset + 4 bytes length + chunk data)
        int maxChunk = (int)Math.min(fileSize, 5L * 1024 * 1024) / cores;
        byte[] buffer = new byte[8 + 4 + maxChunk];

        // 5) 告知 GUI 總量並啟動回呼
        if (cb != null) cb.onStart(fileSize);

        // 6) 主迴圈：持續 receive 並 dispatch 給 pool
        while (totalReceived.get() < fileSize) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            ds.receive(packet);

            // 必須複製 data / length 以免覆寫
            byte[] dataCopy = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), 0, dataCopy, 0, packet.getLength());

            pool.submit(() -> {
                try {
                    ByteBuffer bb = ByteBuffer.wrap(dataCopy);
                    long offset = bb.getLong();
                    int  length = bb.getInt();
                    byte[] chunk = new byte[length];
                    bb.get(chunk);

                    // 同步寫入檔案
                    synchronized (raf) {
                        raf.seek(offset);
                        raf.write(chunk);
                    }

                    totalReceived.addAndGet(length);
                    if (cb != null) {
                        cb.onProgress(length);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    // 若要中斷，可考慮呼叫 pool.shutdownNow()
                }
            });
        }

        // 7) 關閉接收與等待所有任務完成
        ds.close();
        pool.shutdown();
        try {
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 8) 完成後關閉檔案並回傳結果
        raf.close();
        return totalReceived.get() >= fileSize;
    }
}
