package AirShit;
import java.io.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

public class CompressFile {
    // 使用可執行緒池執行非同步壓縮任務。
    private static final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    // 同步方法：使用 GZIPOutputStream 壓縮檔案。
    public static void compressFileGZIP(String source, String target) throws IOException {
        File inputFile = new File(source);
        try (FileInputStream fis = new FileInputStream(inputFile);
             FileOutputStream fos = new FileOutputStream(target);
             GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
            byte[] buffer = new byte[100000];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                gzos.write(buffer, 0, len);
            }
        }
    }

    // 非同步方法：使用 GZIPOutputStream 進行壓縮。
    public static Future<?> compressFileGZIPAsync(String source, String target) {
        return executor.submit(() -> {
            try {
                compressFileGZIP(source, target);
            } catch (IOException e) {
                throw new RuntimeException("Compression failed: " + e.getMessage(), e);
            }
        });
    }

    // 當應用程式關閉時，可呼叫 shutdown() 來關閉執行緒池。
    public static void shutdown() {
        executor.shutdown();
    }
}