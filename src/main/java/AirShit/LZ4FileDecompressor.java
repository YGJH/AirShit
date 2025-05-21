package AirShit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import net.jpountz.lz4.LZ4FrameInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.utils.IOUtils;

public class LZ4FileDecompressor {

    /**
     * 將指定的 .tar.lz4 檔案解壓縮到指定的輸出資料夾。
     *
     * @param inputTarLz4FilePath 要解壓縮的 .tar.lz4 檔案路徑。
     * @param outputFolderPath    解壓縮後檔案存放的目標資料夾路徑。
     * @return 成功時返回 true，失敗時返回 false。
     */
    public static boolean decompressTarLz4Folder(String inputTarLz4FilePath, String outputFolderPath) {
        File inputFile = new File(inputTarLz4FilePath);
        if (!inputFile.exists() || !inputFile.isFile()) {
            System.err.println("錯誤：輸入的 .tar.lz4 檔案不存在或不是一個有效的檔案 -> " + inputTarLz4FilePath);
            return false;
        }

        File outputFolder = new File(outputFolderPath);
        if (!outputFolder.exists()) {
            if (!outputFolder.mkdirs()) {
                System.err.println("錯誤：無法建立輸出資料夾 -> " + outputFolderPath);
                return false;
            }
        } else if (!outputFolder.isDirectory()) {
            System.err.println("錯誤：指定的輸出路徑已存在但不是一個資料夾 -> " + outputFolderPath);
            return false;
        }

        try (FileInputStream fis = new FileInputStream(inputFile);
             LZ4FrameInputStream lz4is = new LZ4FrameInputStream(fis);
             TarArchiveInputStream taris = new TarArchiveInputStream(lz4is)) {

            TarArchiveEntry entry;
            while ((entry = taris.getNextTarEntry()) != null) {
                if (!taris.canReadEntryData(entry)) {
                    System.err.println("錯誤：無法讀取 TAR 條目數據 -> " + entry.getName());
                    continue;
                }

                String entryName = entry.getName();
                File outputFile = new File(outputFolder, entryName);

                if (entry.isDirectory()) {
                    if (!outputFile.exists()) {
                        if (!outputFile.mkdirs()) {
                            System.err.println("警告：無法建立目錄 -> " + outputFile.getAbsolutePath());
                        }
                    }
                } else {
                    // 確保父目錄存在
                    File parentDir = outputFile.getParentFile();
                    if (parentDir != null && !parentDir.exists()) {
                        if (!parentDir.mkdirs()) {
                            System.err.println("警告：無法建立父目錄 -> " + parentDir.getAbsolutePath());
                            // 繼續嘗試寫入檔案，如果父目錄建立失敗，寫入操作可能會失敗
                        }
                    }

                    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                        IOUtils.copy(taris, fos);
                    } catch (IOException e) {
                        System.err.println("錯誤：寫入檔案時發生錯誤 -> " + outputFile.getAbsolutePath() + " : " + e.getMessage());
                        // 可以選擇是否因為單個檔案寫入失敗而終止整個解壓縮過程
                        // return false;
                    }
                }
            }
            System.out.println("檔案成功解壓縮至：" + outputFolderPath);
            return true;

        } catch (IOException e) {
            System.err.println("解壓縮檔案 " + inputTarLz4FilePath + " 時發生錯誤：" + e.getMessage());
            e.printStackTrace();
            // (可選) 清理可能已部分解壓縮的檔案
            // deleteDirectory(outputFolder); // 如果需要回滾
            return false;
        }
    }

    /**
     * 輔助方法：遞迴刪除資料夾及其內容。
     * (與 LZ4FileCompressor 中的方法相同，可以考慮提取到共用工具類)
     */
    private static void deleteDirectory(File directory) {
        File[] allContents = directory.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    if (!file.delete()) {
                        System.err.println("警告：無法刪除檔案 -> " + file.getAbsolutePath());
                    }
                }
            }
        }
        if (!directory.delete()) {
            System.err.println("警告：無法刪除目錄 -> " + directory.getAbsolutePath());
        }
    }

    // 如何使用的範例：
    // public static void main(String[] args) {
    //     System.out.println("Java LZ4 資料夾解壓縮測試開始...");

    //     String compressedArchiveName = "test_compress_folder.tar.lz4"; // 假設這個檔案由 LZ4FileCompressor 產生
    //     String decompressedOutputFolderName = "test_decompress_output";

    //     // 執行解壓縮
    //     // 先確保測試環境乾淨
    //     File outputDir = new File(decompressedOutputFolderName);
    //     if (outputDir.exists()) {
    //         deleteDirectory(outputDir);
    //     }
    //     outputDir.mkdirs(); // 重新建立空的輸出目錄

    //     File archiveFile = new File(compressedArchiveName);
    //     if (!archiveFile.exists()) {
    //         System.err.println("測試中止：壓縮檔 " + compressedArchiveName + " 不存在。請先執行 LZ4FileCompressor 的 main 方法產生它。");
    //         return;
    //     }


    //     boolean success = decompressTarLz4Folder(compressedArchiveName, decompressedOutputFolderName);

    //     if (success) {
    //         System.out.println("\n檔案成功解壓縮至: " + decompressedOutputFolderName);
    //         // (可選) 驗證解壓縮後的內容
    //         File originalFile1 = new File(decompressedOutputFolderName, "test_compress_folder/file1.txt");
    //         File originalFile2 = new File(decompressedOutputFolderName, "test_compress_folder/subdir/file2.txt");

    //         if (originalFile1.exists() && originalFile2.exists()) {
    //             System.out.println("  -> 驗證：找到預期的檔案 file1.txt 和 file2.txt。");
    //             try {
    //                 String content1 = new String(Files.readAllBytes(originalFile1.toPath()));
    //                 System.out.println("  -> file1.txt 內容片段: " + content1.substring(0, Math.min(content1.length(), 30)).replace("\n", " "));
    //             } catch (IOException e) {
    //                 System.err.println("讀取驗證檔案時出錯: " + e.getMessage());
    //             }
    //         } else {
    //             System.err.println("  -> 驗證失敗：未找到所有預期的檔案。");
    //         }

    //     } else {
    //         System.out.println("\n檔案解壓縮失敗。");
    //     }

    //     // (可選) 清理解壓縮的資料夾
    //     // System.out.println("\n清理解壓縮的測試資料...");
    //     // if (outputDir.exists()) {
    //     //     deleteDirectory(outputDir);
    //     // }
    //     System.out.println("\nJava LZ4 資料夾解壓縮測試結束。");
    // }
}