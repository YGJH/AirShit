package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.nio.channels.Channels;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
            // 一個連線處理整個 chunk (offset, length)
            sendChunk(serverHost, serverPort, filePath, chunkOffset, chunkSize, callback);
        }

        /**
         * 真正的 chunk 傳送邏輯：開啟 SocketChannel、先送 offset+length header，等 ACK，
         * 再將檔案 chunk 資料以 FileChannel 讀取後寫進 SocketChannel。
         */
        private void sendChunk(String serverHost, int serverPort, String filePath, long offset, long length, TransferCallback callback) {
            try (SocketChannel channel = SocketChannel.open();
                 RandomAccessFile raf = new RandomAccessFile(filePath, "r");
                 FileChannel inCh = raf.getChannel()) {
                channel.configureBlocking(true);
                channel.connect(new InetSocketAddress(serverHost, serverPort));
                // wrap streams for header/ACK
                var out = Channels.newOutputStream(channel);
                var in = Channels.newInputStream(channel);
                var dos = new DataOutputStream(out);
                var dis = new DataInputStream(in);
                // 1. send header
                dos.writeLong(offset);
                dos.writeLong(length);
                dos.flush();
                // 2. wait ACK
                long ackOff = dis.readLong();
                long ackLen = dis.readLong();
                if (ackOff != offset || ackLen != length) return;
                // 3. send data via zero-copy
                long sent = 0;
                while (sent < length) {
                    long n = inCh.transferTo(offset + sent, length - sent, channel);
                    if (n <= 0) break;
                    sent += n;
                }
                if (callback != null) callback.onProgress(sent);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
