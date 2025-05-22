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
// import org.apache.commons.compress.utils.IOUtils; // Remove this line
import org.apache.commons.io.IOUtils; 

public class LZ4FileCompressor {

    /**
     * 將指定的資料夾壓縮成一個 .tar.lz4 檔案。
     * 大型檔案 (>2MB) 會被收集到 filesArray 中，而不是包含在 .tar.lz4 內。
     * .tar.lz4 檔案本身也會被加入到 filesArray 的末尾。
     *
     * @param sourceFolderPath     要處理的來源資料夾路徑。
     * @param outputTarLz4FilePath 輸出的 .tar.lz4 檔案的完整路徑 (用於存放小型檔案)。
     * @param filesArray           用於存放大型檔案 File 物件以及最後的 .tar.lz4 File 物件的陣列。
     * @return 成功時返回加入到 filesArray 中的檔案總數 (大型檔案 + .tar.lz4 檔案本身)，失敗時返回 0。
     */
    public static int compressFolderToTarLz4(String sourceFolderPath, String outputTarLz4FilePath, File[] filesArray) {
        File sourceFolder = new File(sourceFolderPath);
        if (!sourceFolder.exists() || !sourceFolder.isDirectory()) {
            System.err.println("錯誤：來源資料夾不存在或不是一個有效的資料夾 -> " + sourceFolderPath);
            return 0;
        }

        int countOfItemsInFilesArray = 0;

        try (FileOutputStream fos = new FileOutputStream(outputTarLz4FilePath);
             LZ4FrameOutputStream lz4os = new LZ4FrameOutputStream(fos, BLOCKSIZE.SIZE_64KB);
             TarArchiveOutputStream taros = new TarArchiveOutputStream(lz4os)) {

            taros.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU); // 支援長檔名

            // 遞迴處理資料夾，將小型檔案加入 taros，大型檔案加入 filesArray
            // addFolderToTarRecursive 會返回加入到 filesArray 中的大型檔案數量 (即下一個可用的索引)
            countOfItemsInFilesArray = addFolderToTarRecursive(sourceFolder.getAbsolutePath(), sourceFolder, taros, filesArray, 0);
            
            // 將 .tar.lz4 壓縮檔案本身也加入到 filesArray 中
            // (即使 .tar.lz4 是空的，如果來源資料夾只包含大型檔案或本身為空，也應該將其視為一個處理結果)
            File archiveFile = new File(outputTarLz4FilePath);
            // 檢查 archiveFile 是否真的被創建 (例如，如果 taros 從未寫入任何 entry，檔案可能為0字節或不存在)
            // 但即使是0字節的tar.lz4，如果LZ4FileCompressor的邏輯是創建它，就應該將其加入列表
            if (countOfItemsInFilesArray < filesArray.length) {
                filesArray[countOfItemsInFilesArray++] = archiveFile;
            } else {
                System.err.println("錯誤：filesArray 陣列空間不足以加入壓縮檔案本身 (" + outputTarLz4FilePath + ")。");
                // 根據需求，這裡可以拋出異常或採取其他錯誤處理
            }
            
            System.out.println("資料夾成功壓縮至：" + outputTarLz4FilePath);
            System.out.println("files (大型檔案 + 壓縮檔): ");
            for (int i = 0; i < countOfItemsInFilesArray; i++) {
                if (filesArray[i] != null) { // 防禦性檢查
                    System.out.println(filesArray[i].getName());
                }
            }

            return countOfItemsInFilesArray; // 返回 filesArray 中實際填充的項目數量

        } catch (IOException e) {
            System.err.println("壓縮資料夾 " + sourceFolderPath + " 時發生錯誤：" + e.getMessage());
            e.printStackTrace();
            // 刪除可能已部分創建的輸出檔案
            File partialOutputFile = new File(outputTarLz4FilePath);
            if (partialOutputFile.exists()) {
                partialOutputFile.delete();
            }
            return 0; // 或許應該返回 countOfItemsInFilesArray，如果部分大型檔案已收集
        }
    }

    /**
     * 遞迴地將檔案和資料夾加入到 TAR 輸出流中。
     * 大於2MB的檔案會被加入到 retFilesArray 中，而不是 TAR 流。
     *
     * @param basePath          來源根資料夾的絕對路徑，用於計算相對路徑。
     * @param currentFileOrDir  目前處理的檔案或資料夾。
     * @param taros             TAR 輸出流 (用於存放小型檔案)。
     * @param retFilesArray     用於存放大型檔案 File 物件的陣列。
     * @param currentIndex      目前在 retFilesArray 中用於存放下一個大型檔案的索引。
     * @return                  處理完畢後，retFilesArray 中下一個可用的索引。
     * @throws IOException      如果發生 I/O 錯誤。
     */
    private static int addFolderToTarRecursive(String basePath, File currentFileOrDir, TarArchiveOutputStream taros, File[] retFilesArray, int currentIndex) throws IOException {
        Path currentPath = currentFileOrDir.toPath();
        Path baseDirPath = Paths.get(basePath);
        // 確保 basePath 是 current 的父路徑或自身，以正確計算相對路徑
        String entryName = baseDirPath.getParent() != null ? baseDirPath.getParent().relativize(currentPath).toString() : currentPath.getFileName().toString();
        // 確保 Windows 路徑分隔符轉換為 Unix 風格
        entryName = entryName.replace(File.separatorChar, '/');

        if (currentFileOrDir.isDirectory()) {
            // 對於目錄，即使其內容可能被單獨處理 (大型檔案)，目錄本身的條目也應加入TAR
            if (!entryName.isEmpty() && !entryName.endsWith("/")) {
                entryName += "/"; // 目錄條目應以 "/" 結尾
            }
            TarArchiveEntry entry = new TarArchiveEntry(currentFileOrDir, entryName);
            taros.putArchiveEntry(entry);
            taros.closeArchiveEntry();

            File[] filesInDir = currentFileOrDir.listFiles();
            if (filesInDir != null) {
                for (File itemInDir : filesInDir) {
                    if (itemInDir.isFile() && itemInDir.length() > 2 * 1024 * 1024) { // 大型檔案
                        if (currentIndex < retFilesArray.length) {
                            retFilesArray[currentIndex++] = itemInDir;
                        } else {
                            System.err.println("錯誤：retFilesArray 陣列空間不足以加入大型檔案: " + itemInDir.getName());
                            // 根據需求，這裡可以拋出異常
                        }
                    } else { // 小型檔案或子目錄
                        // 遞迴處理，小型檔案會被加入 taros，子目錄會進一步遞迴
                        // currentIndex 會被正確地傳遞和更新
                        currentIndex = addFolderToTarRecursive(basePath, itemInDir, taros, retFilesArray, currentIndex);
                    }
                }
            }
        } else if (currentFileOrDir.isFile()) { // 這是要加入到 TAR 的小型檔案
            TarArchiveEntry entry = new TarArchiveEntry(currentFileOrDir, entryName);
            taros.putArchiveEntry(entry);
            try (FileInputStream fis = new FileInputStream(currentFileOrDir)) {
                IOUtils.copy(fis, taros, 8192); 
            }
            taros.closeArchiveEntry();
        }
        return currentIndex; // 返回更新後的索引，供上一層呼叫使用
    }

// ... (deleteDirectory 和 main 方法等其他程式碼) ...
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