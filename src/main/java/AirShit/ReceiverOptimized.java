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

import org.apache.commons.io.IOIndexedException;

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
            TransferCallback cb) throws InterruptedException {
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
        // 用虛擬執行緒接收每個 chunk，直到總 byte 數達到 fileLength
        java.util.concurrent.atomic.AtomicLong bytesReceived = new java.util.concurrent.atomic.AtomicLong(0);
        ExecutorService chunkExecutor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            serverSocket.configureBlocking(true);
            while (bytesReceived.get() < fileLength) {
                SocketChannel clientChannel = serverSocket.accept();
                if (clientChannel != null) {
                    chunkExecutor.submit(() -> {
                        try {
                            // 1. header
                            ByteBuffer metaBuf = ByteBuffer.allocate(Long.BYTES * 2);
                            while (metaBuf.hasRemaining()) clientChannel.read(metaBuf);
                            metaBuf.flip();
                            long offset = metaBuf.getLong();
                            long length = metaBuf.getLong();
                            // 2. ACK
                            ByteBuffer ackBuf = ByteBuffer.allocate(Long.BYTES * 2);
                            ackBuf.putLong(offset).putLong(length).flip();
                            clientChannel.write(ackBuf);
                            // 3. data
                            try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
                                 FileChannel outCh = raf.getChannel()) {
                                ByteBuffer buf = ByteBuffer.allocate(8192);
                                long written = 0;
                                long pos = offset;
                                while (written < length) {
                                    int r = clientChannel.read(buf);
                                    if (r <= 0) break;
                                    buf.flip();
                                    outCh.write(buf, pos);
                                    pos += r;
                                    written += r;
                                    buf.clear();
                                    if (cb != null) cb.onProgress(r);
                                }
                                bytesReceived.addAndGet(written);
                            }
                            clientChannel.close();
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    });
                }
            }
            // 等待所有 chunk 完成
            chunkExecutor.shutdown();
            chunkExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS);
            System.out.println("所有 chunk 處理完成，接收器結束。");
            if (cb != null) cb.onComplete(outputFile);
            return true;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }

    }

    private void ReceiverWorker(SocketChannel clientChannel) {
        try {
            // 連續接收多個 chunk: 先 handshake header，再 ACK，再讀資料
            while (true) {
                // 1. 讀取 header: offset + length (皆 long)
                ByteBuffer metaBuf = ByteBuffer.allocate(Long.BYTES * 2);
                while (metaBuf.hasRemaining()) {
                    int r = clientChannel.read(metaBuf);
                    if (r == -1) return; // client 關閉
                }
                metaBuf.flip();
                long offset = metaBuf.getLong();
                long length = metaBuf.getLong();
                System.out.println("收到 metadata => offset: " + offset + ", length: " + length);
                // 2. 回覆 ACK
                ByteBuffer ackBuf = ByteBuffer.allocate(Long.BYTES * 2);
                ackBuf.putLong(offset).putLong(length).flip();
                while (ackBuf.hasRemaining()) clientChannel.write(ackBuf);
                System.out.println("已回覆 ACK => offset: " + offset + ", length: " + length);
                // 3. 讀取資料並寫入檔案
                try (RandomAccessFile raf = new RandomAccessFile(this.outputFile, "rw");
                     FileChannel outChannel = raf.getChannel()) {
                    ByteBuffer buf = ByteBuffer.allocate(8192);
                    long writePos = offset;
                    long totalRead = 0;
                    while (totalRead < length) {
                        int n = clientChannel.read(buf);
                        if (n == -1) return; // client 關閉
                        buf.flip();
                        outChannel.write(buf, writePos);
                        writePos += n;
                        totalRead += n;
                        buf.clear();
                        if (cb != null) cb.onProgress(n);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Receiver handling data failed: " + e.getMessage());
        } finally {
            try { clientChannel.close(); } catch (IOException e) { e.printStackTrace(); }
        }
    }

}
