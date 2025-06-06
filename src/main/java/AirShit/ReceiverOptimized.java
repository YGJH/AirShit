package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReceiverOptimized {

    // 監聽的 TCP port
    private int SERVER_PORT ;
    // 最終要寫入的檔案名稱 (建議與來源檔同大小預先建好)
    private String OUTPUT_FILE ;
    public static TransferCallback transferCallback; // 傳輸回調介面，用於通知傳輸進度或結果
    // 伺服端固定執行緒池大小 (處理多個 client 連線)
    private int SERVER_THREAD_COUNT;
    /** 預設單個 chunk 大小 (與 SendFileOptimized 保持一致) */
    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    /** 預期接收的 chunk 數量，若 <=0 則無限循環 */
    private int expectedChunks;
    public ReceiverOptimized(int SERVER_PORT , String OUTPUT_FILE , int threadCount , TransferCallback callback) {
        this.SERVER_PORT = SERVER_PORT;
        this.SERVER_THREAD_COUNT = threadCount;
        this.OUTPUT_FILE = OUTPUT_FILE;
        ReceiverOptimized.transferCallback = callback;
        this.expectedChunks = -1;
    }
    /** 用於設定預期 chunk 數量的構造器 */
    public ReceiverOptimized(int SERVER_PORT, String OUTPUT_FILE, int threadCount, TransferCallback callback, int expectedChunks) {
        this(SERVER_PORT, OUTPUT_FILE, threadCount, callback);
        this.expectedChunks = expectedChunks;
    }
    public void start() {
        // 建立固定執行緒池，處理每個進來的 client 連線
        //ExecutorService serverPool = Executors.newFixedThreadPool(SERVER_THREAD_COUNT);
        ExecutorService serverPool = Executors.newVirtualThreadPerTaskExecutor(); 

        // 使用 try-with-resources 管理 ServerSocketChannel, RandomAccessFile, FileChannel
        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
             RandomAccessFile raf = new RandomAccessFile(OUTPUT_FILE, "rw");
             FileChannel outFileChannel = raf.getChannel()) {

            serverChannel.bind(new InetSocketAddress(SERVER_PORT));
            // System.out.println("伺服端啟動，監聽 port = " + SERVER_PORT);

            // 若 OUTPUT_FILE 尚未存在或大小為 0，可在此預先創建空檔，並設定到適當大小
            // 也可以先不動，等第一個 chunk 進來再調整大小。
            if (expectedChunks > 0) {
                // 限定接收次數
                for (int i = 0; i < expectedChunks; i++) {
                    SocketChannel clientChannel = serverChannel.accept();
                    serverPool.submit(() -> handleClient(clientChannel, outFileChannel));
                }
                // 等待所有 chunk 處理結束，再關閉 FileChannel
                serverPool.shutdown();
                try {
                    serverPool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                }
            } else {
                // 無限循環接收
                while (true) {
                    SocketChannel clientChannel = serverChannel.accept();
                    // spawn a virtual thread that will live just long enough to drain this chunk
                    Thread.startVirtualThread(() -> handleClient(clientChannel, outFileChannel));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            serverPool.shutdown();
            try {
                serverPool.awaitTermination(Long.MAX_VALUE, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (InterruptedException ie) {
                ie.printStackTrace();
            }
        }
    }

    /**
     * 處理每個 client 連線：先讀 offset, length，回 ACK，然後再讀 chunk 資料並寫進檔案。
     *
     * @param clientChannel 與客戶端溝通的 SocketChannel
     * @param outFileChannel 用來寫入檔案的 FileChannel
     */
    private static void handleClient(SocketChannel clientChannel, FileChannel outFileChannel) {
        try (SocketChannel channel = clientChannel) {
            ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
            // 讀取 offset + length
            while (headerBuffer.hasRemaining()) {
                if (channel.read(headerBuffer) == -1) return;
            }
            headerBuffer.flip();
            long offset = headerBuffer.getLong();
            int length = headerBuffer.getInt();
            // System.out.println("準備接收 chunk，offset=" + offset + ", length=" + length);

            // 回傳 ACK (1 byte)，代表伺服端準備好接收 chunk
            ByteBuffer ackBuf = ByteBuffer.allocate(1);
            ackBuf.put((byte) 1);
            ackBuf.flip();
            while (ackBuf.hasRemaining()) {
                channel.write(ackBuf);
            }

            // 接著從管道讀取 length bytes 的 chunk 資料
            long bytesToReceive = length;
            // 使用絕對位置寫入避免共享 channel 的 position 干擾
            ByteBuffer dataBuffer = ByteBuffer.allocate(64 * 1024); // 64 KB 暫存
            long writePosition = offset;
            while (bytesToReceive > 0) {
                dataBuffer.clear();
                int toRead = (int) Math.min(dataBuffer.capacity(), bytesToReceive);
                dataBuffer.limit(toRead);
                int r = channel.read(dataBuffer);
                if (r == -1) {
                    return;
                }
                dataBuffer.flip();
                // 寫入檔案於指定位置
                try {
                    outFileChannel.write(dataBuffer, writePosition);
                } catch (java.nio.channels.ClosedChannelException cce) {
                    // FileChannel closed prematurely, abort this chunk
                    return;
                }

                if (transferCallback != null) {
                    transferCallback.onProgress(r);
                }
                writePosition += r;

                bytesToReceive -= r;
            }
            outFileChannel.force(false); // 確保資料寫入磁碟
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}