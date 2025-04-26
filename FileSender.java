// FileSender.java
package AirShit;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
public class FileSender {
    private static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024; // 8 MB
    private static final Semaphore SEM = new Semaphore(4);
    private static AtomicLong sent = new AtomicLong(0); // for debugging
    private final String targetHost;
    private final int    targetPort;
    private final ExecutorService executor;

    /** targetClient = "host:port", e.g. "192.168.1.50:9001" */
    public FileSender(String targetClient) {
        String[] parts = targetClient.split(":");
        this.targetHost = parts[0];
        this.targetPort = Integer.parseInt(parts[1]);
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("sender-thread");
            return t;
        });
    }

    /**
     * 1) TCP handshake on targetPort  
     * 2) If accepted, schedule one task per file—or one per chunk if single file.
     */
    public void sendFiles(File[] files, String folderName, TransferCallback callback)
            throws IOException {
        boolean singleFile = (files.length == 1);
        long fileSize = singleFile ? files[0].length() : 0;
        int  chunkCount = singleFile
                ? (int)((fileSize + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE)
                : 1;

        // --- 1) TCP handshake ---
        StringBuilder sb = new StringBuilder(folderName);
        if (singleFile) {
            sb.append("|")
              .append(files[0].getName())
              .append("|")
              .append(fileSize)
              .append("|")
              .append(chunkCount);
        } else {
            for (File f : files) sb.append("|").append(f.getName());
        }

        try (Socket hs = new Socket(targetHost, targetPort);
             DataOutputStream dos = new DataOutputStream(hs.getOutputStream());
             DataInputStream  dis = new DataInputStream (hs.getInputStream())) {

            // send handshake payload
            dos.writeUTF(sb.toString());
            dos.flush();

            // wait for accept/decline
            String resp = dis.readUTF().trim();
            if (!"ACCEPT".equals(resp)) {
                System.out.println("Receiver declined transfer.");
                return;
            }
        }

        // --- 2) schedule transfers ---
        if (singleFile) {
            File f = files[0];
            for (int i = 0; i < chunkCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        SEM.acquire();
                        sendChunk(f, idx, callback);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        SEM.release();
                    }
                });
            }
        } else {
            for (File f : files) {
                executor.submit(() -> sendWholeFile(f, callback));
            }
        }
    }

    private void sendWholeFile(File file, TransferCallback cb) {
        try (Socket sock = new Socket(targetHost, targetPort);
             BufferedOutputStream bos = new BufferedOutputStream(sock.getOutputStream(),64*1024);
             DataOutputStream dos = new DataOutputStream(bos);
             FileInputStream fis = new FileInputStream(file)) {

            sock.setTcpNoDelay(true);
            sock.setSendBufferSize(64*1024);
            sock.setSoTimeout(30_000);
            long total = file.length();

            cb.onStart(file.getName(), total);
            dos.writeUTF(file.getName());
            dos.writeBoolean(false);
            dos.writeLong(total);

            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) {
                dos.write(buf, 0, r);
                long cumul = sent.addAndGet(r);
                cb.onProgress(file.getName(), cumul);
            }
            dos.flush();
            cb.onComplete(file.getName());
        } catch (Exception e) {
            cb.onError(file.getName(), e);
        }
    }

    private void sendChunk(File file, int idx, TransferCallback cb) {
        try (Socket sock = new Socket(targetHost, targetPort);
             DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
             RandomAccessFile raf = new RandomAccessFile(file, "r")) {

            long fileSize = raf.length();
            long offset   = (long)idx * MAX_CHUNK_SIZE;
            long length   = Math.min(MAX_CHUNK_SIZE, fileSize - offset);
            String tag    = file.getName() + "[chunk " + idx + "]";

            cb.onStart(tag, length);
            dos.writeUTF(file.getName());
            dos.writeBoolean(true);
            dos.writeInt(idx);
            dos.writeLong(offset);
            dos.writeLong(length);
            raf.seek(offset);

            byte[] buf = new byte[8192];
            while (sent.get() < length) {
                int toRead = (int)Math.min(buf.length, length - sent.get());
                int r = raf.read(buf, 0, toRead);
                if (r < 0) break;
                dos.write(buf, 0, r);
                long cumul = sent.addAndGet(r);
                cb.onProgress(tag, cumul);
            }
            dos.flush();
            cb.onComplete(tag);
        } catch (Exception e) {
            cb.onError(file.getName() + "[chunk " + idx + "]", e);
        }
    }
}
