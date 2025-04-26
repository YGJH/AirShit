// FileReceiver.java
package AirShit;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

public class FileReceiver {
    private static final int   TCP_BACKLOG = 50;
    private final int          udpPort;
    private final int          tcpPort;
    private final File         saveDir;
    private final ExecutorService executor;

    /** @param saveDirectory where incoming files/chunks will be written */
    public FileReceiver(int udpPort, int tcpPort, File saveDirectory) {
        this.udpPort  = udpPort;
        this.tcpPort  = tcpPort;
        this.saveDir  = saveDirectory;
        if (!saveDir.exists()) saveDir.mkdirs();
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("receiver-thread");
            return t;
        });
    }

    /** Blocks for the UDP handshake, then spins up TCP handlers forever. */
    public void start(TransferCallback callback) throws IOException {
        boolean single;
        String folderName;
        List<String> fileNames;
        long fileSize = 0;
        int  chunkCount = 1;

        // 1) UDP handshake
        try (DatagramSocket ds = new DatagramSocket(udpPort)) {
            byte[] buf = new byte[4096];
            DatagramPacket pkt = new DatagramPacket(buf, buf.length);
            System.out.println("Waiting for transfer announcement...");
            ds.receive(pkt);

            String msg = new String(pkt.getData(), 0, pkt.getLength(), "UTF-8");
            String[] parts = msg.split("\\|");
            folderName = parts[0];
            if (parts.length == 4) {
                // single-file, chunked
                single     = true;
                fileNames  = Collections.singletonList(parts[1]);
                fileSize   = Long.parseLong(parts[2]);
                chunkCount = Integer.parseInt(parts[3]);
            } else {
                // normal multi-file
                single     = false;
                fileNames  = Arrays.asList(parts).subList(1, parts.length);
            }

            // prompt user
            System.out.println("Incoming transfer: " + folderName);
            System.out.println("Files: " + fileNames);
            System.out.print("Accept? (y/N): ");
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            boolean accept = "y".equalsIgnoreCase(br.readLine().trim());

            // send response
            String resp = accept ? "ACCEPT" : "DECLINE";
            byte[] rp = resp.getBytes("UTF-8");
            DatagramPacket ack = new DatagramPacket(
                rp, rp.length, pkt.getAddress(), pkt.getPort());
            ds.send(ack);

            if (!accept) {
                System.out.println("Transfer declined.");
                return;
            }

            // for a chunked single file, pre-create and set full length
            if (single) {
                File out = new File(saveDir, fileNames.get(0));
                try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                    raf.setLength(fileSize);
                }
            }
        }

        // 2) TCP server
        ServerSocket server = new ServerSocket(tcpPort, TCP_BACKLOG);
        System.out.println("Starting TCP listener on port " + tcpPort);

        while (true) {
            Socket client = server.accept();
            executor.submit(() -> handleClient(client, callback));
        }
    }

    private void handleClient(Socket sock, TransferCallback cb) {
        try (DataInputStream dis = new DataInputStream(
                 new BufferedInputStream(sock.getInputStream()))) {

            String fileName = dis.readUTF();
            boolean isChunk = dis.readBoolean();

            if (!isChunk) {
                // whole-file
                long total = dis.readLong();
                cb.onStart(fileName, total);
                File outFile = new File(saveDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    long rec = 0;
                    int  r;
                    while (rec < total && (r = dis.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                        rec += r;
                        cb.onProgress(fileName, rec);
                    }
                }
                cb.onComplete(fileName);

            } else {
                // chunked
                int    idx    = dis.readInt();
                long   offset = dis.readLong();
                long   length = dis.readLong();
                String tag    = fileName + "[chunk " + idx + "]";

                cb.onStart(tag, length);
                File outFile = new File(saveDir, fileName);

                // write into the pre-sized file at the correct offset
                try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
                     FileChannel   ch  = raf.getChannel()) {

                    byte[] buf = new byte[8192];
                    long rec = 0;
                    while (rec < length) {
                        int toRead = (int)Math.min(buf.length, length - rec);
                        int r = dis.read(buf, 0, toRead);
                        if (r < 0) break;
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, r);
                        ch.write(bb, offset + rec);
                        rec += r;
                        cb.onProgress(tag, rec);
                    }
                }
                cb.onComplete(tag);
            }
        } catch (Exception e) {
            cb.onError("receiver", e);
        }
    }
}
