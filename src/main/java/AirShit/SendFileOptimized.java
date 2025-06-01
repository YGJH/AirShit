package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SendFileOptimized {
    
    private String serverHost;
    private int serverPort;
    private String filePath;
    private int threadCount;
    private TransferCallback callback;
    private final long chunkSize = 4L * 1024 * 1024;               // 4MB 單位的 chunk 大小
    private final long MAX_CHUNK_SIZE = 6L * 1024 * 1024 * 1024;    // 最大每輪處理 6GB
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
            int round = (int) Math.ceil((double) fileSize / MAX_CHUNK_SIZE);
            for(int i = 0 ; i < round ; i++) {
                long RoundSize = Math.min(MAX_CHUNK_SIZE, fileSize - i * MAX_CHUNK_SIZE);
                long numChunks = (long) ((RoundSize + chunkSize - 1) / chunkSize);
                long chunkPerThread = (long) Math.ceil((double) numChunks / threadCount);
                // 3. 建立固定執行緒池
                ExecutorService fixedPool = Executors.newFixedThreadPool(threadCount);
                // 4. 依序為每個 chunk 提交一個任務給固定執行緒池
                for (int j = 0; j < threadCount; j++) {
                    // 計算本輪第 j 執行緒要處理的區段偏移及長度
                    long offset = i * MAX_CHUNK_SIZE + j * chunkSize * chunkPerThread;
                    if (offset >= fileSize) {
                        break; // 無更多資料
                    }
                    long length = Math.min(chunkSize * chunkPerThread, fileSize - offset);
                    fixedPool.submit(new ChunkSenderTask(
                            serverHost, serverPort, filePath, offset, length, callback
                    ));
                }
                fixedPool.shutdown();
                while (!fixedPool.isTerminated()) {
                    Thread.sleep(100); // 每 100ms 檢查一次
                }


            }

            // 5. 關閉 fixedPool，不再接受新任務，並等待所有任務完成
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
        private final long chunkOffset;
        private final long chunkSize;
        private static TransferCallback callback;
        public ChunkSenderTask(String serverHost, int serverPort, String filePath, long chunkOffset , long chunkSize, TransferCallback callback) {
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.filePath = filePath;
            this.chunkOffset = chunkOffset;
            this.chunkSize = chunkSize;
            ChunkSenderTask.callback = callback;
        }

        @Override
        public void run() {
            ExecutorService virtualThreadPool = Executors.newVirtualThreadPerTaskExecutor();
            // 直接呼叫 sendChunk，裡面會為每個 packet 建立 vpool
            for(int i = 0; i < chunkSize; i += 8192) {
                long offset = chunkOffset + i * 8192;
                long length = Math.min(8192, chunkSize - i);
                virtualThreadPool.submit(() -> sendChunk(serverHost, serverPort, filePath, offset, length, callback));
            }
        }

        /**
         * 真正的 chunk 傳送邏輯：開啟 SocketChannel、先送 offset+length header，等 ACK，
         * 再將檔案 chunk 資料以 FileChannel 讀取後寫進 SocketChannel。
         */
        private void sendChunk(String serverHost, int serverPort, String filePath, long offset, long length, TransferCallback callback) {
            try (SocketChannel channel = SocketChannel.open()) {
                channel.configureBlocking(true);
                channel.connect(new InetSocketAddress(serverHost, serverPort));
                // 1. 傳送 header: offset + length
                ByteBuffer header = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
                header.putLong(offset);
                header.putInt((int) length);
                header.flip();
                while (header.hasRemaining()) channel.write(header);
                // 2. 等待 ACK
                ByteBuffer ackBuf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
                while (ackBuf.hasRemaining()) {
                    int r = channel.read(ackBuf);
                    if (r == -1) return; // connection closed
                }
                ackBuf.flip();
                long ackOff = ackBuf.getLong();
                int ackLen = ackBuf.getInt();
                // 確認 ACK 正確，可重試
                while (ackOff != offset || ackLen != (int) length) {
                    ackBuf.clear();
                    while (ackBuf.hasRemaining()) channel.read(ackBuf);
                    ackBuf.flip();
                    ackOff = ackBuf.getLong();
                    ackLen = ackBuf.getInt();
                }
                // 3. 傳送資料
                try (RandomAccessFile raf = new RandomAccessFile(filePath, "r");
                     FileChannel inCh = raf.getChannel()) {
                    ByteBuffer buf = ByteBuffer.allocate((int) length);
                    inCh.read(buf, offset);
                    buf.flip();
                    while (buf.hasRemaining()) channel.write(buf);
                    if (callback != null) callback.onProgress(length);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
