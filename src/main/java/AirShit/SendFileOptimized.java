package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
public class SendFileOptimized {
    
    private String serverHost;
    private int serverPort;
    private String filePath;
    private int threadCount;
    private TransferCallback callback;
    private int chunkSize = 40 * 1024 * 1024 ; // 單位：bytes

    public SendFileOptimized(
        String serverHost,
        int serverPort,
        String filePath,
        int threadCount,
        TransferCallback callback
    ) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.filePath = filePath;
        this.threadCount = threadCount;
        this.callback = callback;
    }
    public void start() {

        try {
            // 1. 打開本地檔案，取得 FileChannel 與檔案大小
            RandomAccessFile raf = new RandomAccessFile(filePath, "r");
            FileChannel fileChannel = raf.getChannel();
            long fileSize = fileChannel.size();

            // 2. 計算要分成多少個 chunk
            int numChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);

            // 3. 建立固定執行緒池
            ExecutorService fixedPool = Executors.newFixedThreadPool(threadCount);
            ArrayList<Thread> threads = new ArrayList<>();
            // 4. 依序為每個 chunk 提交一個任務給固定執行緒池
            for (int i = 0; i < numChunks; i++) {
                long offset = (long) i * chunkSize;
                // 最後一個 chunk 的長度可能小於 chunkSize
                int length = (int) Math.min(chunkSize, fileSize - offset);

                // 每個 chunk 單獨開一個 FileChannel
                // fixedPool.submit(new ChunkSenderTask(
                //         serverHost, serverPort, filePath, offset, length, callback
                // ));
           
                Thread t = new Thread(new ChunkSenderTask(
                        serverHost, serverPort, filePath, offset, length, callback
                ));
               threads.add(t);
            }

            for(Thread t : threads) {
                fixedPool.submit(t);
            }
            // 5. 關閉 fixedPool，不再接受新任務，並等待所有任務完成
            fixedPool.shutdown();
            while (!fixedPool.isTerminated()) {
                Thread.sleep(100); // 每 100ms 檢查一次
            }

            // 關閉主文件通道
            fileChannel.close();
            raf.close();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * ChunkSenderTask：負責在固定執行緒中再建立一個 VirtualThreadPerTaskExecutor
     * 來啟動真正的虛擬執行緒送該 chunk。
     */
    private static class ChunkSenderTask implements Runnable {
        private final String serverHost;
        private final int serverPort;
        private final String filePath;
        private final long offset;
        private final int length;
        private static TransferCallback callback;
        public ChunkSenderTask(String serverHost, int serverPort, String filePath, long offset, int length, TransferCallback callback) {
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.filePath = filePath;
            this.offset = offset;
            this.length = length;
            ChunkSenderTask.callback = callback;
        }

        @Override
        public void run() {
            // 在每個固定執行緒內部，建立一個 VirtualThreadPerTaskExecutor
            ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();
            // 將實際傳送 chunk 的任務提交給 virtualPool
            virtualPool.submit(() -> {
                sendChunk();
            });

            // 關閉 virtualPool，不再接受新任務，等該虛擬執行緒完成
            virtualPool.shutdown();
            try {
                while (!virtualPool.isTerminated()) {
                    Thread.sleep(50);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        /**
         * 真正的 chunk 傳送邏輯：開啟 SocketChannel、先送 offset+length header，等 ACK，
         * 再將檔案 chunk 資料以 FileChannel 讀取後寫進 SocketChannel。
         */
        private void sendChunk() {
            try (SocketChannel channel = SocketChannel.open()) {
                channel.configureBlocking(true);
                channel.connect(new InetSocketAddress(serverHost, serverPort));
                System.out.println("[" + Thread.currentThread().getName() + "] 已連到伺服端 " + serverHost + ":" + serverPort
                        + "，準備傳送 chunk offset=" + offset + ", length=" + length);

                // 1. 傳送 header：offset (8 bytes) + length (4 bytes)
                ByteBuffer headerBuf = ByteBuffer.allocate(Long.BYTES + Long.BYTES);
                headerBuf.putLong(offset);
                headerBuf.putLong(length);
                headerBuf.flip();
                while (headerBuf.hasRemaining()) {
                    channel.write(headerBuf);
                }

                // 2. 等待伺服端回傳 ACK (8 bytes)
                ByteBuffer ackBuf = ByteBuffer.allocate(Long.BYTES + Long.BYTES);
                int r = channel.read(ackBuf);
                if (r < Long.BYTES + Long.BYTES) {
                    return;
                }
                ackBuf.flip();
                long ackOffset = ackBuf.getLong();
                long ackLength = ackBuf.getLong();
                while (ackOffset != offset || ackLength != length) {
                    ackBuf.clear();
                    r = channel.read(ackBuf);
                    ackBuf.flip();
                    ackOffset = ackBuf.getLong();
                    ackLength = ackBuf.getLong();
                }

                // 3. 傳送實際 chunk 資料 (長度為 length)，為每個 chunk 打開自己的 FileChannel
                try (RandomAccessFile rafChunk = new RandomAccessFile(filePath, "r");
                     FileChannel chunkChannel = rafChunk.getChannel()) {
                    long remaining = length;
                    long pos = offset;
                    while (remaining > 0) {
                        long transferred = chunkChannel.transferTo(pos, remaining, channel);
                        if (transferred <= 0) {
                            break; // 無法繼續傳送
                        }
                        if (callback != null) {
                            callback.onProgress(transferred);
                        }
                        pos += transferred;
                        remaining -= transferred;
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
