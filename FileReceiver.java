package AirShit;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;


public class FileReceiver {
    private static final int TCP_BACKLOG = 50;
    private final int port;
    private File saveDir;
    private final ExecutorService executor;
    // ← one global counter for all threads:
    private final AtomicLong totalReceived = new AtomicLong(0);

    public FileReceiver(int targetPort) {
        this.port    = targetPort;
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("receiver-thread");
            return t;
        });
    }

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
            boolean singleFile = names.size() == 1;

            // ask user for permission to receive files:
            JOptionPane pane = new JOptionPane(
                    "Accept file transfer from " + hsSock.getInetAddress() + "?\n" +
                    "Files: " + names + "\n" +
                    "Total size: " + (fileSize / 1024) + " KB",
                    JOptionPane.QUESTION_MESSAGE,
                    JOptionPane.YES_NO_OPTION,
                    null, null, null);
            JDialog dialog = pane.createDialog("File Transfer Request");

            dialog.setVisible(true);
            Object selectedValue = pane.getValue();
            boolean accept = selectedValue != null && selectedValue.equals(JOptionPane.YES_OPTION);
            
            if(accept) {
                // ask user for save directory:
                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setDialogTitle("Select Save Directory");
                chooser.setApproveButtonText("Select");
                chooser.setAcceptAllFileFilterUsed(false);
                chooser.setCurrentDirectory(new File(System.getProperty("user.home")));
                chooser.setSelectedFile(new File(folder));
                chooser.setVisible(true);
                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    saveDir = chooser.getSelectedFile();
                } else {
                    System.out.println("No directory selected. Transfer declined.");
                    server.close();
                    return;
                }
            }
            if(singleFile) {
                // don't create directory if single file:
                saveDir = new File(saveDir, names.get(0)).getParentFile();
            } else {
                if (!saveDir.exists()) {
                    saveDir.mkdirs();
                }
            }

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
                cb.onStart(total);

                byte[] buffer = new byte[8192];
                int    r;
                while ((r = dis.read(buffer)) != -1) {
                    // write to file...
                    // -----------------------------------
                    // **global** progress update:
                    long overall = totalReceived.addAndGet(r);
                    cb.onProgress(overall);
                    if (overall >= total) break;
                }

            } else {
                // chunk header
                int    idx    = dis.readInt();
                long   offset = dis.readLong();
                long   length = dis.readLong();
                String tag    = fileName + "[chunk " + idx + "]";

                cb.onStart(length);

                File outFile = new File(saveDir, fileName);
                try (RandomAccessFile raf = new RandomAccessFile(outFile, "rw");
                     FileChannel   ch  = raf.getChannel()) {

                    byte[] buf = new byte[8192];
                    int    r;
                    long   written = 0;
                    while ((r = dis.read(buf)) != -1 && written < length) {
                        ByteBuffer bb = ByteBuffer.wrap(buf, 0, r);
                        ch.write(bb, offset + written);
                        written += r;
                        // **global** progress update:
                        long overall = totalReceived.addAndGet(r);
                        cb.onProgress(overall);
                    }
                }
            }
        } catch (Exception e) {
            cb.onError(e);
        }
    }
}
