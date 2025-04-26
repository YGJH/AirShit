// FileReceiver.java
package AirShit;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

public class FileReceiver {
    private static final int TCP_BACKLOG = 50;
    private final int          port;
    private  File         saveDir;
    private final ExecutorService executor;

    /** @param targetPort  the single TCP port for both handshake & data */
    public FileReceiver(int targetPort, File saveDirectory) {
        this.port     = targetPort;
        this.saveDir  = saveDirectory;
        if (!saveDir.exists()) saveDir.mkdirs();
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
    public void start(TransferCallback callback) throws IOException {
        ServerSocket server = new ServerSocket(port, TCP_BACKLOG);

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
            folder = parts[0];

            if (parts.length == 4) {
                // single‐file chunked
                names      = Collections.singletonList(parts[1]);
                fileSize   = Long.parseLong(parts[2]);
                chunkCount = Integer.parseInt(parts[3]);
            } else {
                // multi‐file
                names = Arrays.asList(parts).subList(1, parts.length);
            }

            // wait for accept/decline and show GUI dialog
            boolean accept = JOptionPane.showConfirmDialog(null, "Accept transfer of " + names + "?", "File Transfer", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
            dos.writeUTF(accept ? "ACCEPT" : "DECLINE");
            dos.flush();
            System.out.println(accept ? "Transfer accepted." : "Transfer declined.");
            if (accept) {
                // create folder if it doesn't exist
                File folderDir = new File(saveDir, folder);
                if (!folderDir.exists()) {
                    folderDir.mkdirs();
                }
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Select Folder to Save Files");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    saveDir = chooser.getSelectedFile();
                } else {
                    System.out.println("No folder selected. Transfer declined.");
                    accept = false;
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

        // --- 3) accept data connections forever ---
        System.out.println("Handshake complete. Waiting for file/chunk connections...");
        while (true) {
            Socket client = server.accept();
            executor.submit(() -> handleClient(client, callback));
        }
    }

    private void handleClient(Socket sock, TransferCallback cb) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(sock.getInputStream()))) {
            String fileName = dis.readUTF();
            boolean isChunk = dis.readBoolean();

            if (!isChunk) {
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
                int    idx    = dis.readInt();
                long   offset = dis.readLong();
                long   length = dis.readLong();
                String tag    = fileName + "[chunk " + idx + "]";

                cb.onStart(tag, length);
                File outFile = new File(saveDir, fileName);
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
