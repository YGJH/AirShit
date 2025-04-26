// FileSender.java
package AirShit;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class FileSender {
    private static final int MAX_CHUNK_SIZE = 8 * 1024 * 1024; // 8 MB

    private final String targetHost;
    private final int    targetUdpPort;
    private final int    targetTcpPort;
    private final ExecutorService executor;

    /** targetClient = "host:udpPort:tcpPort", e.g. "192.168.1.50:9000:9001" */
    public FileSender(String targetClient) {
        String[] parts = targetClient.split(":");
        this.targetHost    = parts[0];
        this.targetUdpPort = Integer.parseInt(parts[1]);
        this.targetTcpPort = Integer.parseInt(parts[2]);
        // cached pool → one thread per chunk/file, reusing idle threads
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("sender-thread");
            return t;
        });
    }

    /**
     * @param files      Array of files to send
     * @param folderName Logical folder name (for handshake)
     * @param callback   Progress / completion / error callbacks
     */
    public void sendFiles(File[] files, String folderName, TransferCallback callback) throws IOException {
        boolean singleFile = (files.length == 1);
        int chunkCount = 1;
        long fileSize = 0;

        if (singleFile) {
            fileSize   = files[0].length();
            chunkCount = (int)((fileSize + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE);
        }

        // 1) UDP handshake
        //    If single file → "folder|name|size|chunks"
        //    Else            → "folder|f1|f2|..."
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
        byte[] payload = sb.toString().getBytes("UTF-8");
        try (DatagramSocket ds = new DatagramSocket()) {
            DatagramPacket pkt = new DatagramPacket(
                payload, payload.length,
                InetAddress.getByName(targetHost), targetUdpPort);
            ds.send(pkt);

            ds.setSoTimeout(10_000);
            DatagramPacket ack = new DatagramPacket(new byte[16], 16);
            ds.receive(ack);
            String resp = new String(ack.getData(), 0, ack.getLength(), "UTF-8").trim();
            if (!"ACCEPT".equals(resp)) {
                System.out.println("Receiver declined transfer.");
                return;
            }
        } catch (SocketTimeoutException ex) {
            System.out.println("No response from receiver; aborting.");
            return;
        }

        // 2) Schedule send tasks
        if (singleFile) {
            File file = files[0];
            for (int idx = 0; idx < chunkCount; idx++) {
                final int chunkIndex = idx;
                executor.submit(() -> sendChunk(file, chunkIndex, callback));
            }
        } else {
            for (File file : files) {
                executor.submit(() -> sendWholeFile(file, callback));
            }
        }
    }

    /** Send one full file in a single thread. */
    private void sendWholeFile(File file, TransferCallback cb) {
        String name = file.getName();
        try (Socket sock = new Socket(targetHost, targetTcpPort);
             DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
             FileInputStream  fis = new FileInputStream(file)) {

            long total = file.length();
            cb.onStart(name, total);

            dos.writeUTF(name);
            dos.writeBoolean(false);    // isChunk = false
            dos.writeLong(total);       // total size

            byte[] buf = new byte[8192];
            long sent = 0;
            int r;
            while ((r = fis.read(buf)) != -1) {
                dos.write(buf, 0, r);
                sent += r;
                cb.onProgress(name, sent);
            }
            dos.flush();
            cb.onComplete(name);
        } catch (Exception e) {
            cb.onError(name, e);
        }
    }

    /** Send one chunk of a single large file. */
    private void sendChunk(File file, int chunkIndex, TransferCallback cb) {
        String name = file.getName();
        try (Socket sock = new Socket(targetHost, targetTcpPort);
             DataOutputStream dos = new DataOutputStream(sock.getOutputStream());
             RandomAccessFile raf = new RandomAccessFile(file, "r")) {

            long fileSize = raf.length();
            long offset   = (long)chunkIndex * MAX_CHUNK_SIZE;
            long length   = Math.min(MAX_CHUNK_SIZE, fileSize - offset);

            cb.onStart(name + "[chunk " + chunkIndex + "]", length);

            dos.writeUTF(name);
            dos.writeBoolean(true);        // isChunk = true
            dos.writeInt(chunkIndex);
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
                cb.onProgress(name + "[chunk " + chunkIndex + "]", sent);
            }
            dos.flush();
            cb.onComplete(name + "[chunk " + chunkIndex + "]");
        } catch (Exception e) {
            cb.onError(name + "[chunk " + chunkIndex + "]", e);
        }
    }
}
