// FileSender.java
package AirShit;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class FileSender {
    private static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024; // 8 MB

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
                executor.submit(() -> sendChunk(f, idx, callback));
            }
        } else {
            for (File f : files) {
                executor.submit(() -> sendWholeFile(f, callback));
            }
        }
    }

    private void sendWholeFile(File file, TransferCallback cb) {
        try (Socket sock = new Socket();
            BufferedOutputStream bos = new BufferedOutputStream(sock.getOutputStream(),64*1024);
            DataOutputStream dos = new DataOutputStream(bos);
            FileInputStream fis = new FileInputStream(file)) {
            
            sock.setTcpNoDelay(true);
            sock.setSendBufferSize(64*1024);
            sock.setSoTimeout(30_000);
            sock.connect(new InetSocketAddress(targetHost,targetPort));

            long total = file.length();
            cb.onStart(file.getName(), total);

            dos.writeUTF(file.getName());
            dos.writeBoolean(false);    // isChunk = false
            dos.writeLong(total);

            byte[] buf = new byte[8192];
            long sent = 0;
            int  r;
            while ((r = fis.read(buf)) != -1) {
                dos.write(buf, 0, r);
                sent += r;
                cb.onProgress(file.getName(), sent);
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

            String tag = file.getName() + "[chunk " + idx + "]";
            cb.onStart(tag, length);

            dos.writeUTF(file.getName());
            dos.writeBoolean(true);        // isChunk = true
            dos.writeInt(idx);
            dos.writeLong(offset);
            dos.writeLong(length);

            raf.seek(offset);
            byte[] buf = new byte[8192];
            long sent = 0;
            while (sent < length) {
                int toRead = (int)Math.min(buf.length, length - sent);
                int r = raf.read(buf, 0, toRead);
                if (r < 0) break;
                dos.write(buf, 0, r);
                sent += r;
                cb.onProgress(tag, sent);
            }
            dos.flush();
            cb.onComplete(tag);
        } catch (Exception e) {
            cb.onError(file.getName() + "[chunk " + idx + "]", e);
        }
    }
}
