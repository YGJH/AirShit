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
    public ReceiverOptimized(int SERVER_PORT , String OUTPUT_FILE , int threadCount , TransferCallback callback) {
        this.SERVER_PORT = SERVER_PORT;
        this.SERVER_THREAD_COUNT = threadCount;
        this.OUTPUT_FILE = OUTPUT_FILE;
        ReceiverOptimized.transferCallback = callback;
    }
    public void start() {
        // 建立固定執行緒池，處理每個進來的 client 連線
        ExecutorService serverPool = Executors.newFixedThreadPool(SERVER_THREAD_COUNT);

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
            serverChannel.bind(new InetSocketAddress(SERVER_PORT));
            // System.out.println("伺服端啟動，監聽 port = " + SERVER_PORT);

            // 若 OUTPUT_FILE 尚未存在或大小為 0，可在此預先創建空檔，並設定到適當大小
            // 也可以先不動，等第一個 chunk 進來再調整大小。
            RandomAccessFile raf = new RandomAccessFile(OUTPUT_FILE, "rw");
            FileChannel outFileChannel = raf.getChannel();

            while (true) {
                // 等待客戶端連線
                SocketChannel clientChannel = serverChannel.accept();
                // System.out.println("收到新連線： " + clientChannel.getRemoteAddress());
                // submit 到伺服端的固定執行緒池中處理
                serverPool.submit(() -> handleClient(clientChannel, outFileChannel));
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            serverPool.shutdown();
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
            // 讀取 offset (8 bytes) + length (4 bytes)
            headerBuffer.clear();
            int readBytes = 0;
            while (headerBuffer.hasRemaining()) {
                int r = channel.read(headerBuffer);
                if (r == -1) {
                    // System.err.println("Client 關閉連線或傳輸異常。");
                    return;
                }
                readBytes += r;
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
            // 在 outFileChannel 上設定 position 為 offset，並將 chunk 寫進去
            outFileChannel.position(offset);
            // 使用一個中介 ByteBuffer 來分多次寫入
            ByteBuffer dataBuffer = ByteBuffer.allocate(64 * 1024); // 64 KB 暫存
            while (bytesToReceive > 0) {
                dataBuffer.clear();
                int toRead = (int) Math.min(dataBuffer.capacity(), bytesToReceive);
                dataBuffer.limit(toRead);
                int r = channel.read(dataBuffer);
                if (r == -1) {
                    // System.err.println("Client 非預期關閉連線，尚未接收完整 chunk。");
                    return;
                }
                dataBuffer.flip();
                // 寫入檔案
                outFileChannel.write(dataBuffer);
                if(transferCallback != null) {
                    transferCallback.onProgress(r);
                }
                bytesToReceive -= r;
            }
            // System.out.println("成功接收並寫入 chunk，offset=" + offset + ", length=" + length);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
