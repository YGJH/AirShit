package AirShit;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance chunk-based file receiver: one connection per chunk, zero-copy writes.
 */
public class ReceiverOptimized {
    private final ServerSocketChannel serverSocket;

    public ReceiverOptimized(ServerSocketChannel serverSocket, int threadCount) {
        this.serverSocket = serverSocket;
    }

    public boolean start(
            String outputFile,
            long fileLength,
            int PORT,
            TransferCallback cb) throws InterruptedException {
        File out = new File(outputFile);
        if (out.exists()) out.delete();
        out.getParentFile().mkdirs();
        try { out.createNewFile(); } catch (IOException e) { return false; }

        AtomicLong bytesReceived = new AtomicLong(0);
        ExecutorService chunkExecutor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            serverSocket.configureBlocking(true);
            while (bytesReceived.get() < fileLength) {
                SocketChannel client = serverSocket.accept();
                if (client == null) continue;
                chunkExecutor.submit(() -> {
                    try {
                        // 1. Read header: offset + length
                        ByteBuffer header = ByteBuffer.allocate(Long.BYTES * 2);
                        while (header.hasRemaining()) client.read(header);
                        header.flip();
                        long offset = header.getLong();
                        long length = header.getLong();

                        // 2. Send ACK
                        ByteBuffer ack = ByteBuffer.allocate(Long.BYTES * 2);
                        ack.putLong(offset).putLong(length).flip();
                        while (ack.hasRemaining()) client.write(ack);

                        // 3. Receive data chunk and write at offset
                        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
                             FileChannel outCh = raf.getChannel()) {
                            ByteBuffer buffer = ByteBuffer.allocate(8192);
                            long written = 0;
                            long pos = offset;
                            while (written < length) {
                                int r = client.read(buffer);
                                if (r <= 0) break;
                                buffer.flip();
                                outCh.write(buffer, pos);
                                pos += r;
                                written += r;
                                buffer.clear();
                                if (cb != null) cb.onProgress(r);
                            }
                            bytesReceived.addAndGet(written);
                        }
                    } catch (IOException ex) {
                        ex.printStackTrace();
                    } finally {
                        try { client.close(); } catch (IOException ignore) {}
                    }
                });
            }
            chunkExecutor.shutdown();
            chunkExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.HOURS);
            if (cb != null) cb.onComplete(outputFile);
            return true;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }
}
