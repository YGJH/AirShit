package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.List;
import java.util.ArrayList;

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

            // 準備初始任務列表
            Queue<ChunkSenderTask> tasksToProcess = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < numChunks; i++) {
                long offset = (long) i * chunkSize;
                int length = (int) Math.min(chunkSize, fileSize - offset);
                // Pass 'i' as chunkIndex
                tasksToProcess.add(new ChunkSenderTask(serverHost, serverPort, fileChannel, i, offset, length));
            }

            int round = 0;
            // 3. 迴圈處理任務
            while (!tasksToProcess.isEmpty()) {
                round++;
                if (round > 1) {
                    System.out.println("Chunk 重傳回合: " + round + ", 剩餘任務數: " + tasksToProcess.size());
                    try { Thread.sleep(1000); } catch (Exception e) {}
                }

                ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
                Semaphore concurrencyLimiter = new Semaphore(threadCount > 0 ? threadCount : 4);
                
                Queue<ChunkSenderTask> activeFailures = new ConcurrentLinkedQueue<>();

                for (ChunkSenderTask task : tasksToProcess) {
                    try {
                        concurrencyLimiter.acquire();
                        pool.submit(() -> {
                            try {
                                boolean success = task.sendChunk();
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

                pool.shutdown();
                while (!pool.isTerminated()) {
                    Thread.sleep(100); 
                }
                
                tasksToProcess.clear();
                tasksToProcess.addAll(activeFailures);

                // 如果本輪結束後，所有任務都成功了 (tasksToProcess 為空)，
                // 進入「確認模式」：向 Receiver 詢問是否有缺漏 (解決 False Positive ACK 問題)
                if (tasksToProcess.isEmpty()) {
                    List<Integer> missingIndices = queryMissingChunks(serverHost, serverPort);
                    if (missingIndices != null && !missingIndices.isEmpty()) {
                        System.out.println("Receiver 報告缺失 " + missingIndices.size() + " 個 chunks，正在重新加入佇列...");
                        for (Integer missingIdx : missingIndices) {
                            long offset = (long) missingIdx * chunkSize;
                            int length = (int) Math.min(chunkSize, fileSize - offset);
                            tasksToProcess.add(new ChunkSenderTask(serverHost, serverPort, fileChannel, missingIdx, offset, length));
                        }
                    }
                }
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
     * 向 Receiver 查詢缺失的 chunks
     */
    private List<Integer> queryMissingChunks(String host, int port) {
        List<Integer> missing = new ArrayList<>();
        try (SocketChannel channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.socket().connect(new InetSocketAddress(host, port), 5000);
            channel.socket().setSoTimeout(10000);

            // Send Type = 1 (QUERY)
            ByteBuffer typeBuf = ByteBuffer.allocate(1);
            typeBuf.put((byte)1);
            typeBuf.flip();
            while(typeBuf.hasRemaining()) channel.write(typeBuf);

            // Read Count
            ByteBuffer countBuf = ByteBuffer.allocate(Integer.BYTES);
            while(countBuf.hasRemaining()) {
                 if (channel.read(countBuf) == -1) return new ArrayList<>(); // Error
            }
            countBuf.flip();
            int count = countBuf.getInt();

            if (count > 0) {
                 // Read list
                 ByteBuffer listBuf = ByteBuffer.allocate(count * Integer.BYTES);
                 while(listBuf.hasRemaining()) {
                     if (channel.read(listBuf) == -1) break;
                 }
                 listBuf.flip();
                 for(int i=0; i<count; i++) {
                     missing.add(listBuf.getInt());
                 }
            }
            return missing;

        } catch (IOException e) {
            // e.printStackTrace();
             System.out.println("Query missing chunks failed: " + e.getMessage());
             return null; // Return null to indicate failure (maybe try again? or just assume ok)
        }
    }

    /**
     * ChunkSenderTask：負責傳送單個 Chunk
     */
    private static class ChunkSenderTask {
        private final String serverHost;
        private final int serverPort;
        private final FileChannel fileChannel;
        private final int chunkIndex; // New: 封包編號
        private final long offset;
        private final int length;

        public ChunkSenderTask(String serverHost, int serverPort, FileChannel fileChannel, int chunkIndex, long offset, int length) {
            this.serverHost = serverHost;
            this.serverPort = serverPort;
            this.fileChannel = fileChannel;
            this.chunkIndex = chunkIndex;
            this.offset = offset;
            this.length = length;
        }

        /**
         * 真正的 chunk 傳送邏輯：
         * 1. Send Type (0)
         * 2. Send ChunkIndex, Offset, Length
         * 3. Send Data
         */
        public boolean sendChunk() {
            int attempts = 0;
            boolean sent = false;
            while (attempts < 3 && !sent) {
                attempts++;
                try (SocketChannel channel = SocketChannel.open()) {
                    channel.configureBlocking(true);
                    channel.socket().connect(new InetSocketAddress(serverHost, serverPort), 5000);
                    
                    // 1. Send Type (0 = DATA)
                    ByteBuffer typeBuf = ByteBuffer.allocate(1);
                    typeBuf.put((byte)0);
                    typeBuf.flip();
                    while (typeBuf.hasRemaining()) channel.write(typeBuf);

                    // 2. 傳送 header：ChunkIndex(4) + offset (8 bytes) + length (4 bytes)
                    ByteBuffer headerBuf = ByteBuffer.allocate(Integer.BYTES + Long.BYTES + Integer.BYTES);
                    headerBuf.putInt(chunkIndex);
                    headerBuf.putLong(offset);
                    headerBuf.putInt(length);
                    headerBuf.flip();
                    while (headerBuf.hasRemaining()) {
                        channel.write(headerBuf);
                    }

                    // 3. 等待伺服端回傳 ACK (1 byte)
                    ByteBuffer ackBuf = ByteBuffer.allocate(1);
                    channel.socket().setSoTimeout(5000); 
                    int r = channel.read(ackBuf);
                    if (r != 1) continue; 
                    ackBuf.flip();
                    if (ackBuf.get() != 1) continue;

                    // 4. 傳送實際 chunk 資料
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
                    ByteBuffer statusBuf = ByteBuffer.allocate(1);
                    // 同樣設定讀取超時
                    channel.socket().setSoTimeout(10000); 
                    int statusRead = channel.read(statusBuf);
                    if (statusRead != 1) {
                        System.err.println("[" + Thread.currentThread().getName() + "] Did not receive status byte, retrying chunk offset=" + offset);
                        continue; // retry
                    }
                    statusBuf.flip();
                    byte status = statusBuf.get();
                    if (status == 1) {
                        sent = true; // success
                    } else {
                        System.err.println("[" + Thread.currentThread().getName() + "] Received NACK for chunk offset=" + offset + ", retrying");
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue; // retry
                    }
                } catch (java.net.SocketTimeoutException ste) {
                    // 針對讀取超時的處理
                    System.err.println("[" + Thread.currentThread().getName() + "] Socket timeout for chunk offset=" + offset + ", retrying");
                    if (attempts >= 3) {
                       // log error but loop will handle retry
                    } else {
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
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
    }
}