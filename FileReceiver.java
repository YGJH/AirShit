// FileReceiver.java
package AirShit;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.*;
import java.util.List;

public class FileReceiver {
    private static int TCP_BACKLOG = 50;
    private final int          port;
    private  File         saveDir;
    private  long       totalSize;
    private final ExecutorService executor;
    private static AtomicLong received = new AtomicLong(0); // for debugging
    private static boolean singleFile;
    /** @param targetPort  the single TCP port for both handshake & data */
    public FileReceiver(int targetPort) {
        this.port     = targetPort;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("receiver-thread");
            return t;
        });
    }

    /**
     * 1) Accept one TCP connection as control channel → handshake  
     * 2) Pre-allocate if needed  
     * 3) Loop accepting further TCP connections, handing each to handleClient()
     */
    
    public void println(String str) {
        System.out.println(str);
    }
    
    public void start(TransferCallback callback) throws IOException {
        ServerSocket server = new ServerSocket(port, TCP_BACKLOG);
        received.set(0); // for debugging
        // --- 1) control handshake ---
        System.out.println("Waiting for TCP handshake on port " + port + "...");
        String folder;
        List<String> names;
        long fileSize = 0;
        int  chunkCount = 1;

        try (Socket hsSock = server.accept();
             DataInputStream dis = new DataInputStream(hsSock.getInputStream());
             DataOutputStream dos = new DataOutputStream(hsSock.getOutputStream())) {

            String msg = dis.readUTF();
            String[] parts = msg.split("\\|");
            // incoming parts[0] may be a full path—keep only the last name element
            folder = new File(parts[0]).getName();
            
            if (parts.length == 4) {
                // single‐file chunked
                names      = Collections.singletonList(parts[1]);
                fileSize   = Long.parseLong(parts[2]);
                chunkCount = Integer.parseInt(parts[3]);
                totalSize  = fileSize;
            } else {
                // multi‐file
                names = Arrays.asList(parts).subList(1, parts.length);
            }
            singleFile = (names.size() == 1);
            // wait for accept/decline and show GUI dialog
            boolean accept = JOptionPane.showConfirmDialog(null, "Accept transfer of " + names + " File Total Size: " + fileSize + "?", "File Transfer" , JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
            dos.writeUTF(accept ? "ACCEPT" : "DECLINE");
            dos.flush();



            if (accept) {
                // let user pick a base directory
                String basePath = FolderSelector
                    .selectFolderAndListFiles(null)
                    .stream().findFirst().map(f -> f.getParent()).orElse(null);
                if (basePath == null) {
                    println("No folder selected. Transfer declined.");
                    accept = false;
                } else {
                    if (singleFile) {
                        // single file → save directly to the base folder
                        saveDir = new File(basePath);
                    } else {
                        // multi-file → create a subfolder named after 'folder'
                        saveDir = new File(basePath, folder);
                    }
                }
            }
            // show dialog to select folder

            if (!accept) {
                System.out.println("Transfer declined.");
                server.close();
                return;
            }
        }

        // --- 2) pre-allocate single‐file if chunked ---
        if (names.size() == 1 && chunkCount > 1) {
            File out = new File(saveDir, names.get(0));
            try (RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
                raf.setLength(fileSize);
            }
        }
        println("saveing to " + saveDir.getAbsolutePath());
        System.out.println("Handshake complete. Waiting for file/chunk connections...");

        // Tell the GUI about total bytes (for single‑file it’s fileSize; multi‑file you’d sum if you extend it)
        callback.onStart(fileSize);

        // --- 3) accept data connections until we hit the expected total ---
        try {
            if(singleFile) {
                // single file → wait for one connection only
                processSingleFileChunks(server, chunkCount, callback);
            } else {
                while (received.get() < fileSize) {
                  Socket client = server.accept();
                  // handle inline if you want deterministic byte counting:
                  handleClient(client, callback);
                }
            }
        } finally {
          // clean up
          server.close();
          println("All data received; receiver shutting down.");
        }
    }

    private void handleClient(Socket sock, TransferCallback cb) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(sock.getInputStream()))) {
            String fileName = dis.readUTF();
            boolean isChunk  = dis.readBoolean();

            if (!isChunk) {
                long total = dis.readLong();
                println(total + " bytes to receive.");
                File outFile = new File(saveDir, fileName);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int  r;
                    while (received.get() < total && (r = dis.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                        long cumul = received.addAndGet(r);             // ← update atomically
                        cb.onProgress(cumul);
                    }
                }

            } else {
                long   offset = dis.readLong();
                long   length = dis.readLong();

                File outFile = new File(saveDir, fileName);
                try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
                     FileChannel   ch  = raf.getChannel()) {

                    byte[] buf = new byte[8192];
                    int    r;
                    while (received.get() < length && (r = dis.read(buf, 0, (int)Math.min(buf.length, length - received.get()))) > 0) {
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, r);
                        ch.write(bb, offset + received.get());
                        long cumul = received.addAndGet(r);             // ← update atomically
                        cb.onProgress(cumul);
                    }
                }
            }
        } catch (Exception e) {
            cb.onError(e);
        }
    }

    /**
     * For a single‐file chunked transfer, accept and handle each chunk
     * in its own thread, then wait until all chunks have been written.
     *
     * @param server     the ServerSocket already bound to the transfer port
     * @param chunkCount number of chunks expected for this single file
     * @param cb         callback to drive GUI progress
     */
    private void processSingleFileChunks(ServerSocket server, int chunkCount, TransferCallback cb) throws IOException {
        CountDownLatch latch = new CountDownLatch(chunkCount);

        for (int i = 0; i < chunkCount; i++) {
            executor.submit(() -> {
                try {
                    // accept one chunk‐connection and write it
                    Socket client = server.accept();
                    handleClient(client, cb);
                } catch (Exception e) {
                    cb.onError(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // wait until all chunk tasks have completed
        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
