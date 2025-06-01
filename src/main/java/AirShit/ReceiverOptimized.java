package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
/**
 * 超高效能檔案接收器，使用 Zero Copy + Virtual Threads + 智能區塊比對
 */
public class ReceiverOptimized {
    private ServerSocketChannel serverSocket;
    private int threadCount;
    private String outputFile;
    private TransferCallback cb;

    public ReceiverOptimized(ServerSocketChannel serverSocket, int threadCount) {
        this.serverSocket = serverSocket;
        this.threadCount = threadCount;
    }

    public boolean start(
            String outputFile,
            long fileLength,
            /* port is already bound externally */
            int PORT,
            TransferCallback cb) {
        this.outputFile = outputFile;
        this.cb = cb;
        File out = new File(this.outputFile);
        if( out.exists() ) {
            out.delete(); // 如果檔案已存在，則刪除它
            try {
                out.getParentFile().mkdirs(); // 確保目錄存在
                out.createNewFile(); // 確保檔案存在
            } catch (IOException e) {
                return false;
            }
        } else {
            try {
                out.getParentFile().mkdirs(); // 確保目錄存在
                out.createNewFile(); // 確保檔案存在
            } catch (IOException e) {
                return false;
            }
        }
        if(fileLength <= 500L * 1024L * 1024L ) { 
            threadCount = 1; // 如果檔案小於 500MB，則只使用單執行緒
        }
        ExecutorService mainThreadPool = Executors.newFixedThreadPool(threadCount); // 管理初始握手

        // 以虛擬執行緒並行處理 chunk data
        ExecutorService vpool = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong bytesReceived = new AtomicLong();
        try {
            // port already bound by FileReceiver; just ensure blocking
            serverSocket.configureBlocking(true);
             
            if (cb != null) cb.onStart(fileLength, outputFile);
            while (bytesReceived.get() < fileLength) {
                SocketChannel client = serverSocket.accept();
                // 讀 header: offset(8) + length(4)
                ByteBuffer header = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
                while (header.hasRemaining()) {
                    if (client.read(header) == -1) throw new IOException("Client closed prematurely");
                }
                header.flip();
                long offset = header.getLong();
                int length = header.getInt();
                System.out.println("收到 metadata => offset: " + offset + ", length: " + length);
                bytesReceived.addAndGet(length);
                // 提交虛擬執行緒處理 chunk data 與 ACK
                vpool.submit(() -> {
                    try {
                        handleChunk(client, offset, length);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    } finally {
                        try { client.close(); } catch (IOException e) {}
                    }
                });
            }
            vpool.shutdown();
            vpool.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS);
            if (cb != null) cb.onComplete(outputFile);
            return true;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        } finally {
            // ensure pool is shut down
            mainThreadPool.shutdown();
        }

    }

    private void ReceiverWorker(SocketChannel clientChannel) {
        try (ExecutorService chunkExecutor = Executors.newVirtualThreadPerTaskExecutor()) {

            // 2. 不關閉 clientChannel，持續從同一條 channel 讀 offset/length，再交給 handleChunk 去讀資料

            while (true) {
                // 先讀 8 bytes 的 offset，再讀 4 bytes 的 length，確保完整接收
                ByteBuffer metaBuf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
                while (metaBuf.hasRemaining()) {
                    int r = clientChannel.read(metaBuf);
                    if (r == -1) {
                        // client 已關閉連線
                        return;
                    }
                }
                metaBuf.flip();
                long offset = metaBuf.getLong();
                int length = metaBuf.getInt();
                System.out.println("收到 metadata => offset: " + offset + ", length: " + length);

                // 3. 為這個 offset/length 提交一個虛擬執行緒，讓它去真正讀 chunk 的資料
                chunkExecutor.submit(() -> handleChunk(clientChannel, offset, length));
            }

        } catch (IOException e) {
            System.err.println("處理 client 握手時發生 IOException: " + e.getMessage());
        } finally {
            // 如果握手階段結束（client 關閉連線），在此關閉 channel
            try {
                clientChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }

    private void handleChunk(SocketChannel clientChannel, long offset, long length) {

        try {
            // reply ACK (offset 8 bytes + length 4 bytes)
            ByteBuffer ackBuf = ByteBuffer.allocate(Long.BYTES + Integer.BYTES);
            ackBuf.putLong(offset);
            ackBuf.putInt((int) length);
            ackBuf.flip();
            while (ackBuf.hasRemaining()) clientChannel.write(ackBuf);
            System.out.println("已回覆 ACK => offset: " + offset + ", length: " + length);

            // 為每個 chunk 打開自己的 RandomAccessFile/FileChannel
            try (RandomAccessFile raf = new RandomAccessFile(this.outputFile, "rw");
                 FileChannel outChannel = raf.getChannel()) {
                raf.seek(offset);
                ByteBuffer buf = ByteBuffer.allocate(8192);
                int totalRead = 0;
                while (totalRead < length) {
                    int n = clientChannel.read(buf);
                    if (n == -1) return; // client 已關閉
                    buf.flip();
                    outChannel.write(buf);
                    totalRead += n;
                    buf.clear();
                    if (cb != null) cb.onProgress(n);
                }
            }
        } catch (IOException e) {
            System.err.println("在虛擬執行緒讀 chunk 時發生 IOException: " + e.getMessage());
        }
    }

}
