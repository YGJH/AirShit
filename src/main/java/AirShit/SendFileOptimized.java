package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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
        return start(null);
    }

    /**
     * Send specific chunks by their chunk index (0-based). If chunkIndexes is null, sends the whole file.
     */
    public boolean start(List<Integer> chunkIndexes) {
        try {
            // 1. 打開本地檔案，取得 FileChannel 與檔案大小
            RandomAccessFile raf = new RandomAccessFile(filePath, "r");
            
            FileChannel fileChannel = raf.getChannel();
            long fileSize = fileChannel.size();
            // System.out.println("檔案大小：" + fileSize + " bytes");
            // 2. 計算要分成多少個 chunk
            int numChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
            // System.out.println("將檔案拆成 " + numChunks + " 個 chunk (每chunk 大小約 " + chunkSize + " bytes)");

            // 準備初始任務列表
            Queue<ChunkSenderTask> tasksToProcess = new ConcurrentLinkedQueue<>();
            if (chunkIndexes == null) {
                for (int i = 0; i < numChunks; i++) {
                    long offset = (long) i * chunkSize;
                    int length = (int) Math.min(chunkSize, fileSize - offset);
                    tasksToProcess.add(new ChunkSenderTask(serverHost, serverPort, fileChannel, offset, length, i));
                }
            } else {
                for (Integer idxObj : chunkIndexes) {
                    if (idxObj == null) continue;
                    int i = idxObj;
                    if (i < 0 || i >= numChunks) continue;
                    long offset = (long) i * chunkSize;
                    int length = (int) Math.min(chunkSize, fileSize - offset);
                    tasksToProcess.add(new ChunkSenderTask(serverHost, serverPort, fileChannel, offset, length, i));
                }
            }

            int round = 0;
            // 3. 迴圈處理任務，直到所有任務成功 (或達到某個總體重試限制，這裡暫不設限)
            while (!tasksToProcess.isEmpty()) {
                round++;
                if (round > 1) {
                    System.out.println("Chunk 重傳回合: " + round + ", 剩餘任務數: " + tasksToProcess.size());
                    try { Thread.sleep(1000); } catch (Exception e) {}
                }

                // 建立固定大小執行緒池 (Virtual Threads)，並搭配 Semaphore 限制併發數
                ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
                Semaphore concurrencyLimiter = new Semaphore(threadCount > 0 ? threadCount : 4);
                
                Queue<ChunkSenderTask> activeFailures = new ConcurrentLinkedQueue<>();

                // 4. 提交任務
                for (ChunkSenderTask task : tasksToProcess) {
                    try {
                        concurrencyLimiter.acquire();
                        pool.submit(() -> {
                            try {
                                boolean success = task.sendChunk(); // 直接呼叫 sendChunk 並取得結果
                                if (!success) {
                                    activeFailures.add(task);
                                }
                            } finally {
                                concurrencyLimiter.release();
                            }
                        });
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                // 5. 等待本輪所有任務完成
                pool.shutdown();
                while (!pool.isTerminated()) {
                    Thread.sleep(100); 
                }
                
                // 更新待處理列表為失敗的任務
                tasksToProcess.clear();
                tasksToProcess.addAll(activeFailures);
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
     * ChunkSenderTask：負責傳送單個 Chunk
     */
    private static class ChunkSenderTask {
        private final String serverHost;
        private final int serverPort;
        private final FileChannel fileChannel;
        private final long offset;
        private final int length;
        private final int chunkIndex;

        public ChunkSenderTask(String serverHost, int serverPort, FileChannel fileChannel, long offset, int length, int chunkIndex) {
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.fileChannel = fileChannel;
            this.offset = offset;
            this.length = length;
            this.chunkIndex = chunkIndex;
        }

        /**
         * 真正的 chunk 傳送邏輯：開啟 SocketChannel、先送 offset+length header，等 ACK，
         * 再將檔案 chunk 資料以 FileChannel 讀取後寫進 SocketChannel。
         * @return boolean true if successful, false otherwise
         */
        public boolean sendChunk() {
            int attempts = 0;
            boolean sent = false;
            while (attempts < 3 && !sent) {
                attempts++;
                try (SocketChannel channel = SocketChannel.open()) {
                    channel.configureBlocking(true);
                    // 增加連線超時設定 (雖然 blocking mode 無法直接設 connect timeout，但操作上會更穩)
                    channel.socket().connect(new InetSocketAddress(serverHost, serverPort), 5000);
                    // System.out.println("[" + Thread.currentThread().getName() + "] 已連到伺服端 " + serverHost + ":" + serverPort
                    //         + "，準備傳送 chunk offset=" + offset + ", length=" + length);

                    // 1. 傳送 header：offset (8 bytes) + length (4 bytes) + chunkIndex (4 bytes)
                    ByteBuffer headerBuf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + Integer.BYTES);
                    headerBuf.putLong(offset);
                    headerBuf.putInt(length);
                    headerBuf.putInt(chunkIndex);
                    headerBuf.flip();
                    while (headerBuf.hasRemaining()) {
                        channel.write(headerBuf);
                    }

                    // 2. 等待伺服端回傳 ACK (1 byte) - use non-blocking read + timeout
                    channel.configureBlocking(false);
                    int ack = readSingleByteWithTimeout(channel, 5000);
                    if (ack != 1) continue; // retry
                    // System.out.println("收到伺服端 ACK，開始傳送 chunk 資料 offset=" + offset);

                    // back to blocking for transferTo
                    channel.configureBlocking(true);

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

                    // 4. 传输数据后读取服务端返回的状态字节，1=成功，0=失败
                    channel.configureBlocking(false);
                    int status = readSingleByteWithTimeout(channel, 15000);
                    if (status == 1) {
                        sent = true; // success
                    } else {
                        System.err.println("[" + Thread.currentThread().getName() + "] Received NACK for chunk offset=" + offset + ", retrying");
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue; // retry
                    }
                } catch (SocketTimeoutException ste) {
                    System.err.println("[" + Thread.currentThread().getName() + "] Timeout for chunkIndex=" + chunkIndex + " offset=" + offset + ", retrying");
                    try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } catch (java.net.SocketException se) {
                    if (attempts >= 3) {
                        se.printStackTrace();
                    } else {
                        // wait before retry
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    // break; // Don't break immediately, try again
                }
            }
            return sent;
        }

        private static int readSingleByteWithTimeout(SocketChannel channel, long timeoutMillis) throws IOException {
            try (Selector selector = Selector.open()) {
                channel.register(selector, SelectionKey.OP_READ);
                int selected = selector.select(timeoutMillis);
                if (selected <= 0) {
                    throw new SocketTimeoutException("timeout");
                }
                ByteBuffer buf = ByteBuffer.allocate(1);
                int r = channel.read(buf);
                if (r != 1) {
                    throw new IOException("EOF/short read");
                }
                buf.flip();
                return buf.get();
            }
        }
    }
}