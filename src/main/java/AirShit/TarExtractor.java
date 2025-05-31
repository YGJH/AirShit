package AirShit;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

public class TarExtractor {
    // 16MB 緩衝區，可依情況調整（越大系統呼叫越少，但佔用記憶體越多）
    private static final int BUFFER_SIZE = 16 * 1024 * 1024;

    /**
     * 將指定的 tar 檔解壓到目標資料夾。
     *
     * @param tarFile  待解壓的 .tar 檔
     * @param destDir  解壓後檔案的輸出資料夾
     * @throws IOException
     */
    public static void decompress(File tarFile, File destDir) throws IOException {
        // 確保輸出目錄存在
        if (!destDir.exists()) {
            destDir.mkdirs();
        } else {
            destDir.delete(); // 如果目標資料夾已存在，則刪除它
            destDir.mkdirs(); // 重新建立目標資料夾
        }

        try (
            FileInputStream fis = new FileInputStream(tarFile);
            BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE);
            TarArchiveInputStream tis = new TarArchiveInputStream(bis)
        ) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];

            // 逐個 entry 讀取
            while ((entry = tis.getNextTarEntry()) != null) {
                Path outputPath = destDir.toPath().resolve(entry.getName());

                if (entry.isDirectory()) {
                    // 建立資料夾
                    Files.createDirectories(outputPath);
                } else {
                    // 確保父目錄存在
                    Path parent = outputPath.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }

                    // 寫出檔案
                    try (OutputStream os = new BufferedOutputStream(
                            Files.newOutputStream(outputPath), BUFFER_SIZE)) {
                        int read;
                        while ((read = tis.read(buffer)) != -1) {
                            os.write(buffer, 0, read);
                        }
                    }
                }
            }
        }
    }

    public static void start(File tarFile, File destDir) {

        long start = System.currentTimeMillis();
        try {
            decompress(tarFile, destDir);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(2);
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("解壓完成，耗時：%d ms%n", elapsed);
    }
}
