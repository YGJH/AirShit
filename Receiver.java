package AirShit;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;



public class Receiver {
    public static void println(String a) {
        System.out.println(a);
    }
    public static boolean start(ServerSocket serverSocket,
                                String outputFile,
                                long fileSize,
                                TransferCallback cb) throws IOException {

        AtomicLong totalReceived = new AtomicLong(0);
        List<Thread> handlers = new ArrayList<>();
        File out = new File(outputFile);
        long baseChunkSize = Math.min(fileSize, 5L*1024*1024) / 8; // 8MB chunk size
        // spawn one handler per chunk
        int chunkCount = (int) fileSize / baseChunkSize; 
        for (int i = 0; i < chunkCount; i++) {
            Thread handler = new Thread(() -> {
                try (
                  Socket sock = serverSocket.accept();
                  DataInputStream dis = new DataInputStream(sock.getInputStream());
                  RandomAccessFile raf = new RandomAccessFile(out, "rw")
                ) {
                    // read exactly what ChunkSender writes:
                    long offset = dis.readLong();
                    int length  = dis.readInt();

                    raf.seek(offset);
                    byte[] buf = new byte[8*1024];
                    int  r, rem = length;
                    while (rem > 0 && (r = dis.read(buf, 0, Math.min(buf.length, rem))) > 0 && rem > 0) {
                        raf.write(buf, 0, r);
                        totalReceived.addAndGet(r);
                        rem -= r;
                        cb.onProgress(r);
                    }
                } catch (IOException e) {
                    System.err.println("Handler 發生錯誤：");
                    out.delete();
                    e.printStackTrace();
                }
            }, "chunk-handler-" + i);

            handler.start();
            handlers.add(handler);
        }

        // wait for all chunk‑handlers
        for (Thread t : handlers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        return totalReceived.get() >= fileSize;
    }
}
