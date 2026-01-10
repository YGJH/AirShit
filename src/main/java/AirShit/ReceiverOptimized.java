package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;

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
        // 使用虛擬執行緒為每個 chunk 連線提供服務
         
         // 使用 try-with-resources 管理 ServerSocketChannel, RandomAccessFile, FileChannel
         try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
              RandomAccessFile raf = new RandomAccessFile(OUTPUT_FILE, "rw");
              FileChannel outFileChannel = raf.getChannel()) {

             serverChannel.bind(new InetSocketAddress(SERVER_PORT));
            // 系統啟動並監聽 port = SERVER_PORT

            // 根據預期 chunk 數量分支
            if (expectedChunks > 0) {
                CountDownLatch latch = new CountDownLatch(expectedChunks);
                // 只要還有未完成的 chunk，就持續接受連線 (支援重傳機制)
                while (latch.getCount() > 0) {
                    SocketChannel clientChannel = serverChannel.accept();
                    Thread.startVirtualThread(() -> {
                        boolean success = handleClient(clientChannel, outFileChannel);
                        if (success) {
                            latch.countDown();
                        }
                    });
                }
                // 等待最後的任務都確認完成 (雖然 latch=0 時通常代表都 decrement 過了，但在並發下是安全的)
                try {
                    latch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                while (true) {
                    SocketChannel clientChannel = serverChannel.accept();
                    Thread.startVirtualThread(() -> handleClient(clientChannel, outFileChannel));
                }
            }
         } catch (IOException e) {
             e.printStackTrace();
        }
     }

    /**
     * 處理每個 client 連線：先讀 offset, length，回 ACK，然後再讀 chunk 資料並寫進檔案。
     *
     * @param clientChannel 與客戶端溝通的 SocketChannel
     * @param outFileChannel 用來寫入檔案的 FileChannel
     * @return boolean 表示該 chunk 是否成功接收並寫入
     */
    private static boolean handleClient(SocketChannel clientChannel, FileChannel outFileChannel) {
        try (SocketChannel channel = clientChannel) {
            channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
            // read header
            ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
            while (headerBuffer.hasRemaining()) {
                if (channel.read(headerBuffer) == -1) return false;
            }
            headerBuffer.flip();
            long offset = headerBuffer.getLong();
            int length = headerBuffer.getInt();

            // send header ACK
            ByteBuffer ackBuf = ByteBuffer.allocate(1);
            ackBuf.put((byte)1).flip();
            while (ackBuf.hasRemaining()) channel.write(ackBuf);

            // data receive
            try {
                ByteBuffer dataBuffer = ByteBuffer.allocateDirect(256 * 1024);
                long bytesToReceive = length;
                long writePosition = offset;
                while (bytesToReceive > 0) {
                    dataBuffer.clear();
                    int toRead = (int)Math.min(dataBuffer.capacity(), bytesToReceive);
                    dataBuffer.limit(toRead);
                    int r = channel.read(dataBuffer);
                    if (r == -1) throw new IOException("Unexpected EOF");
                    dataBuffer.flip();
                    outFileChannel.write(dataBuffer, writePosition);
                    if (transferCallback != null) transferCallback.onProgress(r);
                    writePosition += r;
                    bytesToReceive -= r;
                }
                outFileChannel.force(false);
                // send success status
                ByteBuffer status = ByteBuffer.allocate(1).put((byte)1).flip();
                while (status.hasRemaining()) channel.write(status);
                return true; // Success
            } catch (IOException ioe) {
                ioe.printStackTrace();
                // send NACK
                try {
                    ByteBuffer nack = ByteBuffer.allocate(1).put((byte)0).flip();
                    while (nack.hasRemaining()) channel.write(nack);
                } catch (IOException ignore){}
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false; // Failure
    }
}
