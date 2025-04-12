package AirShit;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

public class UnzipProgram {

    // ExecutorService to handle unzip tasks concurrently.
    private static final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // Synchronous method to unzip the file from source (a .gz file) to the target file.
    public static void unzipFile(String source, String target) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzis.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
            }
        }
    }

    // Asynchronous method to unzip the file using multiple threads.
    public static Future<?> unzipFileAsync(String source, String target) {
        return executor.submit(() -> {
            try {
                unzipFile(source, target);
            } catch (IOException e) {
                throw new RuntimeException("Unzip failed: " + e.getMessage(), e);
            }
        });
    }

    // Optional: Call this method when your application is closing to shutdown the executor.
    public static void shutdown() {
        executor.shutdown();
    }
}