package AirShit;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ReceiverOptimized {
    private final int serverPort;
    private final String outputFile;
    public static TransferCallback transferCallback;

    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;

    private final int totalChunks;
    private final BitSet receivedChunks;
    private final long idleTimeoutMillis;
    private final Runnable onListening;
    private final AtomicBoolean senderDone;

    /**
     * @param totalChunks total chunks for the whole file (0-based chunk indexes: 0..totalChunks-1)
     * @param receivedChunks shared BitSet to mark received chunks across rounds
     * @param idleTimeoutMillis when no new chunks arrive for this duration (and no in-flight handlers), return to caller
     * @param onListening optional callback invoked after bind() completes
     */
    public ReceiverOptimized(int serverPort, String outputFile, int threadCount, TransferCallback callback,
            int totalChunks, BitSet receivedChunks, long idleTimeoutMillis, Runnable onListening, AtomicBoolean senderDone) {
        this.serverPort = serverPort;
        this.outputFile = outputFile;
        ReceiverOptimized.transferCallback = callback;
        this.totalChunks = Math.max(0, totalChunks);
        this.receivedChunks = receivedChunks;
        this.idleTimeoutMillis = Math.max(1000, idleTimeoutMillis);
        this.onListening = onListening;
        this.senderDone = senderDone;
    }

    /** Backward-compatible constructor: single round receive, no missing tracking. */
    public ReceiverOptimized(int serverPort, String outputFile, int threadCount, TransferCallback callback, int expectedChunks) {
        this(serverPort, outputFile, threadCount, callback, expectedChunks, new BitSet(expectedChunks), 15000, null, null);
    }

    /**
     * Receives chunks until either all chunks have been received, or idle timeout is reached.
     * Returns true if the file is complete (all chunks received).
     */
    public boolean start() {
        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicLong lastProgress = new AtomicLong(System.currentTimeMillis());

        if (safeCardinality(receivedChunks) >= totalChunks) {
            return true;
        }

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open();
                RandomAccessFile raf = new RandomAccessFile(outputFile, "rw");
                FileChannel outFileChannel = raf.getChannel()) {

            serverChannel.setOption(java.net.StandardSocketOptions.SO_REUSEADDR, true);
            serverChannel.bind(new InetSocketAddress(serverPort));
            serverChannel.configureBlocking(false);

            if (onListening != null) {
                try {
                    onListening.run();
                } catch (Exception ignore) {
                }
            }

            while (safeCardinality(receivedChunks) < totalChunks) {
                SocketChannel clientChannel = serverChannel.accept();
                if (clientChannel == null) {
                    if (safeCardinality(receivedChunks) >= totalChunks) {
                        break;
                    }
                    // Important: before sender indicates it's done, do not time out or request missing.
                    if (senderDone != null && !senderDone.get()) {
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        continue;
                    }

                    if (inFlight.get() == 0 && (System.currentTimeMillis() - lastProgress.get()) > idleTimeoutMillis) {
                        break;
                    }
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }

                inFlight.incrementAndGet();
                Thread.startVirtualThread(() -> {
                    try {
                        boolean ok = handleClient(clientChannel, outFileChannel);
                        if (ok) {
                            lastProgress.set(System.currentTimeMillis());
                        }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }

            // wait for in-flight handlers to finish
            long waitStart = System.currentTimeMillis();
            while (inFlight.get() > 0 && (System.currentTimeMillis() - waitStart) < 30000) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            return safeCardinality(receivedChunks) >= totalChunks;
        } catch (IOException e) {
            e.printStackTrace();
            return safeCardinality(receivedChunks) >= totalChunks;
        }
    }

    private static int safeCardinality(BitSet bs) {
        if (bs == null) return 0;
        synchronized (bs) {
            return bs.cardinality();
        }
    }

    /**
     * Protocol: header = offset(long) + length(int) + chunkIndex(int), then raw chunk bytes.
     */
    private boolean handleClient(SocketChannel clientChannel, FileChannel outFileChannel) {
        try (SocketChannel channel = clientChannel) {
            channel.configureBlocking(true);
            channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);

            ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES + Integer.BYTES + Integer.BYTES);
            while (headerBuffer.hasRemaining()) {
                if (channel.read(headerBuffer) == -1) return false;
            }
            headerBuffer.flip();
            long offset = headerBuffer.getLong();
            int length = headerBuffer.getInt();
            int chunkIndex = headerBuffer.getInt();

            if (chunkIndex < 0 || chunkIndex >= totalChunks || length < 0) {
                sendStatus(channel, (byte) 0);
                return false;
            }

            // ACK header
            sendAck(channel);

            try {
                ByteBuffer dataBuffer = ByteBuffer.allocateDirect(256 * 1024);
                long bytesToReceive = length;
                long writePosition = offset;
                while (bytesToReceive > 0) {
                    dataBuffer.clear();
                    int toRead = (int) Math.min(dataBuffer.capacity(), bytesToReceive);
                    dataBuffer.limit(toRead);
                    int r = channel.read(dataBuffer);
                    if (r == -1) throw new IOException("Unexpected EOF");
                    dataBuffer.flip();
                    while (dataBuffer.hasRemaining()) {
                        int written = outFileChannel.write(dataBuffer, writePosition);
                        if (written <= 0) {
                            throw new IOException("FileChannel.write returned " + written);
                        }
                        writePosition += written;
                    }
                    if (transferCallback != null) transferCallback.onProgress(r);
                    bytesToReceive -= r;
                }

                synchronized (receivedChunks) {
                    receivedChunks.set(chunkIndex);
                }

                // send success status
                sendStatus(channel, (byte) 1);
                return true;
            } catch (IOException ioe) {
                ioe.printStackTrace();
                try {
                    sendStatus(channel, (byte) 0);
                } catch (IOException ignore) {
                }
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void sendAck(SocketChannel channel) throws IOException {
        ByteBuffer ackBuf = ByteBuffer.allocate(1);
        ackBuf.put((byte) 1).flip();
        while (ackBuf.hasRemaining()) channel.write(ackBuf);
    }

    private static void sendStatus(SocketChannel channel, byte status) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(1);
        buf.put(status).flip();
        while (buf.hasRemaining()) channel.write(buf);
    }
}
