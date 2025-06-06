package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SendFileOptimized {
    private String serverHost ;
    private int serverPort ;
    private String filePath;
    
    private int threadCount;
    public static TransferCallback transferCallback; // 傳輸回調介面，用於通知傳輸進度或結果
    private final int chunkSize;// 單位：bytes
    private  final int DEFAULT_CHUNK_SIZE = 1024 * 1024; // 預設每個 chunk 大小為 1MB
    public SendFileOptimized(String serverHost, int serverPort, String filePath, int threadCount, TransferCallback transferCallback) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.filePath = filePath;
        this.threadCount = threadCount;
        this.chunkSize = DEFAULT_CHUNK_SIZE;
        SendFileOptimized.transferCallback = transferCallback;

    }
    public boolean start() {
        try {
            // 1. 打開本地檔案，取得 FileChannel 與檔案大小
            RandomAccessFile raf = new RandomAccessFile(filePath, "r");
            
            FileChannel fileChannel = raf.getChannel();
            long fileSize = fileChannel.size();
            // System.out.println("檔案大小：" + fileSize + " bytes");
            // 2. 計算要分成多少個 chunk
            int numChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
            // System.out.println("將檔案拆成 " + numChunks + " 個 chunk (每chunk 大小約 " + chunkSize + " bytes)");

            // 3. 建立固定大小執行緒池，以限制同時連線數
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

            // 4. 提交每個 chunk 任務給固定大小的執行緒池
            for (int i = 0; i < numChunks; i++) {
                long offset = (long) i * chunkSize;
                // 最後一個 chunk 的長度可能小於 chunkSize
                int length = (int) Math.min(chunkSize, fileSize - offset);

                pool.submit(new ChunkSenderTask(
                        serverHost, serverPort, fileChannel, offset, length
                ));
            }

            // 5. 關閉 thread pool，不再接受新任務，並等待所有任務完成
            pool.shutdown();
            while (!pool.isTerminated()) {
                Thread.sleep(100); // 每 100ms 檢查一次
            }

            // System.out.println("所有 chunk 傳送完畢！");
            fileChannel.close();
            raf.close();
            return true; // 傳送成功
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
        return false; // 傳送失敗
    }

    /**
     * ChunkSenderTask：負責在固定執行緒中再建立一個 VirtualThreadPerTaskExecutor
     * 來啟動真正的虛擬執行緒送該 chunk。
     */
    private static class ChunkSenderTask implements Runnable {
        private final String serverHost;
        private final int serverPort;
        private final FileChannel fileChannel;
        private final long offset;
        private final int length;

        public ChunkSenderTask(String serverHost, int serverPort, FileChannel fileChannel, long offset, int length) {
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.fileChannel = fileChannel;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public void run() {
            sendChunk();

            return; // 虛擬執行緒結束後，回到 ChunkSenderTask 的 run 方法
        }

        /**
         * 真正的 chunk 傳送邏輯：開啟 SocketChannel、先送 offset+length header，等 ACK，
         * 再將檔案 chunk 資料以 FileChannel 讀取後寫進 SocketChannel。
         */
        private void sendChunk() {
            int attempts = 0;
            boolean sent = false;
            while (attempts < 3 && !sent) {
                attempts++;
                try (SocketChannel channel = SocketChannel.open()) {
                    channel.configureBlocking(true);
                    channel.connect(new InetSocketAddress(serverHost, serverPort));
                    // System.out.println("[" + Thread.currentThread().getName() + "] 已連到伺服端 " + serverHost + ":" + serverPort
                    //         + "，準備傳送 chunk offset=" + offset + ", length=" + length);

                    // 1. 傳送 header：offset (8 bytes) + length (4 bytes)
                    ByteBuffer headerBuf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
                    headerBuf.putLong(offset);
                    headerBuf.putInt(length);
                    headerBuf.flip();
                    while (headerBuf.hasRemaining()) {
                        channel.write(headerBuf);
                    }

                    // 2. 等待伺服端回傳 ACK (1 byte)
                    ByteBuffer ackBuf = ByteBuffer.allocate(1);
                    int r = channel.read(ackBuf);
                    if (r != 1) {
                        // System.err.println("未收到正確的 ACK，chunk offset=" + offset + " 取消傳送");
                        return;
                    }
                    ackBuf.flip();
                    byte ack = ackBuf.get();
                    if (ack != 1) {
                        // System.err.println("ACK 回傳內容異常：" + ack + "，chunk offset=" + offset + " 取消傳送");
                        return;
                    }
                    // System.out.println("收到伺服端 ACK，開始傳送 chunk 資料 offset=" + offset);

                    // 3. 傳送實際 chunk 資料 (長度為 length)
                    //    直接利用 FileChannel.transferTo 搭配 SocketChannel，能更有效率
                    long remaining = length;
                    long pos = offset;
                    while (remaining > 0) {
                        long transferred = fileChannel.transferTo(pos, remaining, channel);
                        if (transferred <= 0) continue;
                        if (transferCallback != null) transferCallback.onProgress(transferred);
                        pos += transferred;
                        remaining -= transferred;
                    }
                    sent = true; // success
                } catch (java.net.SocketException se) {
                    if (attempts >= 3) {
                        se.printStackTrace();
                    } else {
                        // wait before retry
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                    break;
                }
            }
        }
    }
}