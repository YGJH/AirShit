package AirShit;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

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
    
    // 用於追蹤已接收的 chunk index，解決重複接收導致計數錯誤的問題
    private final Set<Integer> receivedChunks = ConcurrentHashMap.newKeySet();

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
             
             serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
             serverChannel.bind(new InetSocketAddress(SERVER_PORT));
            // 系統啟動並監聽 port = SERVER_PORT

            // 根據預期 chunk 數量分支
            if (expectedChunks > 0) {
                CountDownLatch latch = new CountDownLatch(expectedChunks);
                
                // [FIX]: 啟動一個監控執行緒，當所有 chunk 都接收完成 (latch歸零) 時，
                // 主動關閉 serverChannel，以強制中斷主執行緒卡在 accept() 的阻塞狀態。
                Thread.startVirtualThread(() -> {
                    try {
                        latch.await();
                        // 任務完成，關閉通道以釋放主執行緒的 accept 阻塞
                        if (serverChannel.isOpen()) {
                            // Give some time for the last ACK to be sent properly? 
                            // Or relies on handleClient to return before latch down.
                            // Adding a small delay just in case.
                            Thread.sleep(200);
                            serverChannel.close();
                        }
                    } catch (IOException | InterruptedException e) {
                        // ignore
                    }
                });

                try {
                    // 只要還有未完成的 chunk，就持續接受連線 (支援重傳機制)
                    // 注意：當 latch 歸零後，上方監控執行緒會關閉 channel，導致這裡拋出異常退出循環
                    while (latch.getCount() > 0) {
                        try {
                            SocketChannel clientChannel = serverChannel.accept();
                            Thread.startVirtualThread(() -> {
                                boolean isNewChunk = handleClient(clientChannel, outFileChannel, expectedChunks, receivedChunks);
                                if (isNewChunk) {
                                    latch.countDown();
                                }
                            });
                        } catch (java.nio.channels.ClosedChannelException | java.nio.channels.AsynchronousCloseException e) {
                            // Channel closed by monitor thread, exit loop
                            break;
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                // 等待最後的 chunk 寫入完成
                try {
                    latch.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                while (true) {
                    SocketChannel clientChannel = serverChannel.accept();
                    Thread.startVirtualThread(() -> handleClient(clientChannel, outFileChannel, -1, receivedChunks));
                }
            }
         } catch (IOException e) {
             e.printStackTrace();
        }
     }

    /**
     * 處理每個 client 連線
     * 協議更新：
     * 1. 讀取 1 byte TYPE (0=DATA, 1=QUERY_MISSING)
     * 
     * 如果是 DATA (0):
     *    讀取 ChunkIndex (int), Offset (long), Length (int)
     *    ... 接收資料 ...
     *    回傳 ACK
     * 
     * 如果是 QUERY_MISSING (1):
     *    計算缺失的 Chunk Index 列表
     *    回傳 Count (int)
     *    回傳 List<Integer>
     * 
     * @return boolean 如果成功接收了一個 *新的* data chunk，返回 true；否則 (重複chunk, query, 失敗) 返回 false
     */
    private static boolean handleClient(SocketChannel clientChannel, FileChannel outFileChannel, int totalChunks, Set<Integer> receivedChunks) {
        try (SocketChannel channel = clientChannel) {
            channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
            
            // 1. Read Type
            ByteBuffer typeBuf = ByteBuffer.allocate(1);
            while(typeBuf.hasRemaining()) {
                 if (channel.read(typeBuf) == -1) return false;
            }
            typeBuf.flip();
            byte type = typeBuf.get();

            if (type == 1) { // QUERY_MISSING
                if (totalChunks <= 0) return false; // Should not happen in fixed mode
                List<Integer> missing = new ArrayList<>();
                for (int i = 0; i < totalChunks; i++) {
                    if (!receivedChunks.contains(i)) {
                        missing.add(i);
                    }
                }
                // Send count
                ByteBuffer resp = ByteBuffer.allocate(Integer.BYTES + missing.size() * Integer.BYTES);
                resp.putInt(missing.size());
                for(Integer id : missing) {
                    resp.putInt(id);
                }
                resp.flip();
                while(resp.hasRemaining()) channel.write(resp);
                return false; // Not a data chunk increment
            }

            // DATA (type == 0)
            // read header: Index(4) + Offset(8) + Length(4) = 16 bytes
            ByteBuffer headerBuffer = ByteBuffer.allocate(Integer.BYTES + Long.BYTES + Integer.BYTES);
            while (headerBuffer.hasRemaining()) {
                if (channel.read(headerBuffer) == -1) return false;
            }
            headerBuffer.flip();
            int chunkIndex = headerBuffer.getInt();
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

                // Mark as received
                // 返回 true 僅當這是一個「新」接收到的 chunk
                return receivedChunks.add(chunkIndex); 
            } catch (IOException ioe) {
                // send NACK
                try {
                    ByteBuffer nack = ByteBuffer.allocate(1).put((byte)0).flip();
                    while (nack.hasRemaining()) channel.write(nack);
                } catch (IOException ignore){}
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false; // Failure or no latch decrement needed
    }
}
