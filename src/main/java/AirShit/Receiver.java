package AirShit;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
public class Receiver {
    private final ServerSocket serverSocket;

    public Receiver(ServerSocket ss) {
        this.serverSocket = ss;
    }

    public boolean start(String outputFile,
                         long fileLength,
                         int threadCount,
                         TransferCallback cb) throws IOException, InterruptedException {
        File out = new File(outputFile);
        out.getParentFile().mkdirs();
        out.createNewFile();

        // 1) 固定 chunk 大小(可改) & 计算总共要拆成多少 chunk
        long baseChunkSize= Math.min(fileLength, 5L * 1024 * 1024 * 1024); // 同 sender
        long workerCount  = (long)Math.ceil((double)fileLength / (double)baseChunkSize);
        int  chunkCount   = (int)(workerCount * threadCount);             // = sender 的 workerCount * threadCount

        // 2) 建一个固定大小的 pool，只同时跑 threadCount 个任务
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        // 3) 主线程 accept chunkCount 次，把每个 Socket 提交给 pool
        for (int i = 0; i < chunkCount; i++) {
            Socket dataSock = serverSocket.accept();
            futures.add(pool.submit(() -> {
                try (
                    DataInputStream dis = new DataInputStream(dataSock.getInputStream());
                    RandomAccessFile raf = new RandomAccessFile(out, "rw")
                ) {
                    // 从 header 读真正的 offset/length
                    long offset = dis.readLong();
                    int  length = dis.readInt();
                    raf.seek(offset);
                    byte[] buf = new byte[8 * 1024];
                    int rem = length, r;
                    while (rem > 0 && (r = dis.read(buf, 0, Math.min(buf.length, rem))) > 0) {
                        raf.write(buf, 0, r);
                        rem -= r;
                        if (cb != null) cb.onProgress(r);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    cb.onError(e);
                    return;
                }
            }));
        }

        // 4) 等所有 chunk 處理完
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);

        // 5) (可選) 驗證 bytes 是否都收完
        boolean allOk = futures.isEmpty()
            ? true
            : futures.stream().allMatch(f -> {
                try {
                    f.get();
                    return true;
                } catch (Exception ex) {
                    ex.printStackTrace();
                    cb.onError(ex);
                    return false;
                }
            });
        return allOk;
    }
    
    // … 省略其他 inner class / 方法 …
}
