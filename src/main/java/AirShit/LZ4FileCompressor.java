package AirShit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;

public class LZ4FileCompressor {

    /**
     * 將指定的資料夾壓縮成一個 .tar.lz4 檔案。
     *
     * @param sourceFolderPath     要壓縮的來源資料夾路徑。
     * @param outputTarLz4FilePath 輸出的 .tar.lz4 檔案的完整路徑。
     * @return 成功時返回輸出的檔案路徑，失敗時返回 null。
     */
    public static String compressFolderToTarLz4(String sourceFolderPath, String outputTarLz4FilePath) {
        File sourceFolder = new File(sourceFolderPath);
        if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
            System.err.println("錯誤：來源資料夾不存在或不是一個有效的資料夾 -> " + sourceFolderPath);
            return null;
        }

        try (FileOutputStream fos = new FileOutputStream(outputTarLz4FilePath);
             LZ4FrameOutputStream lz4os = new LZ4FrameOutputStream(fos, BLOCKSIZE.SIZE_64KB);
             TarArchiveOutputStream taros = new TarArchiveOutputStream(lz4os)) {

            // TAR 檔案中的條目名稱需要是相對路徑
            // TarArchiveOutputStream 預設使用 UTF-8 編碼條目名稱
            taros.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU); // 支援長檔名
            addFolderToTar(sourceFolder.getAbsolutePath(), sourceFolder, taros);

            System.out.println("資料夾成功壓縮至：" + outputTarLz4FilePath);
            return outputTarLz4FilePath;

        } catch (IOException e) {
            System.err.println("壓縮資料夾 " + sourceFolderPath + " 時發生錯誤：" + e.getMessage());
            e.printStackTrace();
            // 刪除可能已部分創建的輸出檔案
            File partialOutputFile = new File(outputTarLz4FilePath);
            if (partialOutputFile.exists()) {
                partialOutputFile.delete();
            }
            return null;
        }
    }

    /**
     * 遞迴地將檔案和資料夾加入到 TAR 輸出流中。
     *
     * @param basePath   來源根資料夾的絕對路徑，用於計算相對路徑。
     * @param current    目前處理的檔案或資料夾。
     * @param taros      TAR 輸出流。
     * @throws IOException 如果發生 I/O 錯誤。
     */
    private static void addFolderToTar(String basePath, File current, TarArchiveOutputStream taros) throws IOException {
        Path currentPath = current.toPath();
        Path baseDirPath = Paths.get(basePath);
        // 確保 basePath 是 current 的父路徑或自身，以正確計算相對路徑
        String entryName = baseDirPath.getParent() != null ? baseDirPath.getParent().relativize(currentPath).toString() : currentPath.getFileName().toString();
        // 確保 Windows 路徑分隔符轉換為 Unix 風格
        entryName = entryName.replace(File.separatorChar, '/');


        if (current.isDirectory()) {
            if (!entryName.isEmpty() && !entryName.endsWith("/")) {
                entryName += "/"; // 目錄條目應以 "/" 結尾
            }
            TarArchiveEntry entry = new TarArchiveEntry(current, entryName);
            taros.putArchiveEntry(entry);
            taros.closeArchiveEntry();

            File[] files = current.listFiles();
            if (files != null) {
                for (File file : files) {
                    addFolderToTar(basePath, file, taros); // 遞迴處理子檔案/資料夾
                }
            }
        } else if (current.isFile()) {
            TarArchiveEntry entry = new TarArchiveEntry(current, entryName);
            // entry.setSize(current.length()); // TarArchiveEntry(File, String) 建構函式會自動設定大小
            taros.putArchiveEntry(entry);
            try (FileInputStream fis = new FileInputStream(current)) {
                IOUtils.copy(fis, taros);
            }
            taros.closeArchiveEntry();
        }
    }


    // 如何使用的範例：
    public static void main(String[] args) {
        System.out.println("Java LZ4 資料夾壓縮測試開始...");

        String sourceFolderName = "test_compress_folder";
        String outputArchiveName = sourceFolderName + ".tar.lz4";

        // 建立測試資料夾和檔案結構
        File sourceFolder = new File(sourceFolderName);
        File subFolder = new File(sourceFolder, "subdir");
        File file1 = new File(sourceFolder, "file1.txt");
        File file2 = new File(subFolder, "file2.txt");
        File file3 = new File(sourceFolder, "another_file.log");

        try {
            // 清理舊的測試資料 (如果存在)
            if (sourceFolder.exists()) {
                deleteDirectory(sourceFolder);
            }
            new File(outputArchiveName).delete();


            sourceFolder.mkdirs();
            subFolder.mkdirs();

            Files.write(file1.toPath(), "這是檔案1的內容。\nHello from file1.".getBytes());
            Files.write(file2.toPath(), "這是子目錄中檔案2的內容。\nGreetings from file2!".getBytes());
            Files.write(file3.toPath(), "日誌檔案內容。\nLog entry 1\nLog entry 2".getBytes());

            System.out.println("測試資料夾結構已建立於: " + sourceFolder.getAbsolutePath());

            // 執行壓縮
            String compressedFilePath = compressFolderToTarLz4(sourceFolder.getAbsolutePath(), outputArchiveName);

            if (compressedFilePath != null) {
                System.out.println("\n資料夾成功壓縮至: " + compressedFilePath);
                File compressedFile = new File(compressedFilePath);
                if (compressedFile.exists()) {
                    System.out.println("  -> 壓縮後檔案大小: " + compressedFile.length() + " bytes");
                }
            } else {
                System.out.println("\n資料夾壓縮失敗。");
            }

        } catch (IOException e) {
            System.err.println("測試過程中發生錯誤: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // (可選) 清理測試資料夾和壓縮檔
            System.out.println("\n清理測試資料...");
            if (sourceFolder.exists()) {
                deleteDirectory(sourceFolder);
            }
            // new File(outputArchiveName).delete(); // 如果壓縮成功，可能希望保留壓縮檔
            System.out.println("\nJava LZ4 資料夾壓縮測試結束。");
        }
    }

    /**
     * 輔助方法：遞迴刪除資料夾及其內容。
     */
    private static void deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
}