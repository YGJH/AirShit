// FileSender.java
package AirShit;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class FileSender {
    private static final int MAX_CHUNK_SIZE = 800 * 1024 * 1024; // 800 MB

    private final String targetHost;
    private final int    targetPort;
    private final ExecutorService executor;
    // ← one global counter for all threads:
    private final AtomicLong totalSent = new AtomicLong(0);

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
 
        try (Socket hs = new Socket(targetHost, targetPort)) {
            DataOutputStream dos = new DataOutputStream(hs.getOutputStream());
            DataInputStream dis  = new DataInputStream(hs.getInputStream());

            dos.writeUTF(sb.toString());
            dos.flush();
            System.out.println("Sent handshake: " + sb.toString());
            String resp = dis.readUTF();
            System.out.println("Receiver response: " + resp);
            if (!"ACCEPT".equals(resp)) {
                System.out.println("Receiver declined transfer.");
                return;
            }
            System.out.println("Receiver accepted transfer.");

        }

        // --- 2) schedule transfers ---
        if (singleFile) {
            File f = files[0];
            for (int i = 0; i < chunkCount; i++) {
                final int idx = i;
                executor.submit(() -> sendChunk(f, idx, callback));
            }
        } else {
            for (File f : files) {
                executor.submit(() -> sendWholeFile(f, callback));
            }
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
    }

    private void sendWholeFile(File file, TransferCallback cb) {
        try (Socket sock = new Socket(targetHost, targetPort);
             DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
             FileInputStream  fis = new FileInputStream(file)) {

            long total = file.length();
            cb.onStart(total);

            dos.writeUTF(file.getName());
            dos.writeBoolean(false);    // isChunk = false
            dos.writeLong(total);

            byte[] buf = new byte[8192];
            int  r;
            while ((r = fis.read(buf)) != -1) {
                dos.write(buf, 0, r);
                // update single, global counter:
                long overall = totalSent.addAndGet(r);
                cb.onProgress(overall);
            }
            dos.flush();
        } catch (Exception e) {
            cb.onError(e);
        }
    }

    private void sendChunk(File file, int idx, TransferCallback cb) {
        try (Socket sock = new Socket(targetHost, targetPort);
             DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
             RandomAccessFile raf = new RandomAccessFile(file, "r")) {

            long fileSize = raf.length();
            long offset   = (long)idx * MAX_CHUNK_SIZE;
            long length   = Math.min(MAX_CHUNK_SIZE, fileSize - offset);

            if(length <= 0) {
                System.out.println("Chunk " + idx + " is empty or out of bounds.");
                return;
            }

            cb.onStart(length);

            dos.writeUTF(file.getName());
            dos.writeBoolean(true);        // isChunk = true
            dos.writeInt(idx);
            dos.writeLong(offset);
            dos.writeLong(length);

            raf.seek(offset);
            byte[] buf = new byte[819200]; // 800 KB
            long sent = 0;
            while (sent < length) {
                int toRead = (int)Math.min(buf.length, length - sent);
                int r = raf.read(buf, 0, toRead);
                if (r < 0) break;
                dos.write(buf, 0, r);
                sent += r;
                // update single, global counter:
                long overall = totalSent.addAndGet(r);
                cb.onProgress(overall);
            }
            dos.flush();
        } catch (Exception e) {
            cb.onError( e);
        }
    }
}
