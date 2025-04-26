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
    public void start(File[] files , String FolderName) throws IOException {
        boolean single;
        String folderName;
        long fileSize = 0;
        int  chunkCount = 1;
        // 1) if folderName is null, then it is a single file transfer
        if (FolderName == null) {
            single = true;
            folderName = files[0].getName();
            fileSize   = files[0].length();
            chunkCount = (int)((fileSize + FileSender.MAX_CHUNK_SIZE - 1) / FileSender.MAX_CHUNK_SIZE);
        } else {
            single = false;
            folderName = FolderName;
            fileSize = 0;
            chunkCount = files.length;
        }
        // check folder is exists
        File folder = new File(saveDir, folderName);
        try {
            if (!folder.exists()) folder.mkdirs();
        } catch (Exception e) {
            System.out.println("Error creating folder: " + folder.getAbsolutePath());
            return;
        }

        // 2) TCP server
        ServerSocket server = new ServerSocket(tcpPort, TCP_BACKLOG);
        System.out.println("Starting TCP listener on port " + tcpPort);

        while (true) {
            Socket client = server.accept();
            executor.submit(() -> handleClient(client));
        }
    }

    private void handleClient(Socket sock) {
        try (DataInputStream dis = new DataInputStream(
                 new BufferedInputStream(sock.getInputStream()))) {

            String fileName = dis.readUTF();
            boolean isChunk = dis.readBoolean();

            if (!isChunk) {
                // whole-file
                long total = dis.readLong();
                File outFile = new File(saveDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    long rec = 0;
                    int  r;
                    while (rec < total && (r = dis.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                        rec += r;
                    }
                }

            } else {
                // chunked
                long   offset = dis.readLong();
                long   length = dis.readLong();

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
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try { sock.close(); } catch (IOException e) { e.printStackTrace(); }
        }
    }
}
