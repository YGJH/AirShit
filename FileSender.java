package AirShit;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FileSender: Handles sending files, including potential compression.
 */
public class FileSender {
    private  String host;
    private  int port;
    private  String initialFatherDir; // 初始的父資料夾，不會被修改

    public FileSender(String host, int port , String fatherDir) {
        this.host = host;
        this.port = port;
        this.initialFatherDir = fatherDir; // 儲存初始的父目錄
    }

    public void println (String str) {
        // Consider using a proper logger or the LogPanel from your UI
        System.out.println(str);
    }

    public void sendFiles(String[] relativeFilePaths, String senderUserName, String subFolderName, TransferCallback callback) throws IOException, InterruptedException {

        // 使用局部變數來處理當前操作的基礎路徑，避免修改類別成員 initialFatherDir
        String currentBasePath = this.initialFatherDir;
        String[] filesToProcess = relativeFilePaths.clone(); // 複製一份檔案列表以供處理

        // handshake
        StringBuilder sb = new StringBuilder();
        long originalTotalSize = 0;
        int threadCount = Runtime.getRuntime().availableProcessors();
        boolean isSingleFileOriginally = filesToProcess.length == 1 && (subFolderName == null || subFolderName.isEmpty());

        if (isSingleFileOriginally) {
            sb.append("isSingle|");
            // 對於單一檔案，filesToProcess[0] 可能是完整路徑或相對路徑
            // 我們需要確保它是相對於 initialFatherDir 的名稱
            File singleFileObj = new File(filesToProcess[0]);
            String singleFileName = singleFileObj.getName(); // 取得檔案名稱
            filesToProcess[0] = singleFileName; // 更新 filesToProcess 陣列只包含檔案名稱

            File actualFile = new File(currentBasePath, singleFileName);
            sb.append(senderUserName).append("|").append(actualFile.getName());
            if (actualFile.exists() && actualFile.isFile()) {
                originalTotalSize = actualFile.length();
            } else {
                callback.onError(new FileNotFoundException("Single file not found or is a directory: " + actualFile.getAbsolutePath()));
                return;
            }
        } else { // 多檔案或資料夾
            // 如果有子資料夾名稱，更新 currentBasePath
            if (subFolderName != null && !subFolderName.isEmpty()) {
                // 安全性注意：subFolderName 應被清理以防止路徑遍歷
                // 例如：subFolderName = sanitizePathSegment(subFolderName);
                currentBasePath = new File(this.initialFatherDir, subFolderName).getAbsolutePath();
            }
            // filesToProcess 陣列中的路徑現在是相對於 currentBasePath 的

            sb.append("isMulti|");
            sb.append(senderUserName).append("|").append(subFolderName != null ? subFolderName : ""); // 傳送原始的 folderName
            for (String relativePath : filesToProcess) {
                // 安全性注意：relativePath 應被清理
                File f = new File(currentBasePath, relativePath);
                if (f.exists() && f.isFile()) {
                    originalTotalSize += f.length();
                    sb.append("|").append(relativePath); // 傳送相對於 folderName 的路徑
                } else {
                     println("Warning: File not found or is a directory, skipping: " + f.getAbsolutePath());
                }
            }
            if (originalTotalSize == 0 && filesToProcess.length > 0) {
                callback.onError(new FileNotFoundException("No valid files found in the selection for multi-file transfer."));
                return;
            }
        }

        sb.append("|").append(originalTotalSize);
        String originalTotalSizeFormatted = SendFileGUI.formatFileSize(originalTotalSize);
        sb.append("|").append(originalTotalSizeFormatted);
        sb.append("|").append(threadCount);

        // 連線到 Receiver 進行 handshake
        try (Socket socket = new Socket(host, port);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            dos.writeUTF(sb.toString());
            dos.flush();
            String response = dis.readUTF();
            if (response.startsWith("ACK")) {
                String serverThreadStr = response.split("\\|")[1];
                int serverThreads = Integer.parseInt(serverThreadStr);
                threadCount = Math.min(threadCount, serverThreads);
            } else {
                callback.onError(new IOException("Receiver rejected handshake: " + response));
                return;
            }
        } catch (IOException e) {
            callback.onError(new IOException("Handshake connection failed: " + e.getMessage(), e));
            return;
        }

        boolean isCompressedSuccessfully = false;
        String[] filesToSendInLoop = filesToProcess; // 預設傳送處理後的檔案列表
        String loopBasePath = currentBasePath; // 迴圈中使用的基礎路徑
        long currentTotalSizeForCallback = originalTotalSize; // callback.onStart 使用的大小

        // 嘗試壓縮: 原始總大小小於 600KB 且原始檔案數量大於 1
        if (originalTotalSize < 600 * 1024 && filesToProcess.length > 1 && !isSingleFileOriginally) {
            // println("Total size " + originalTotalSizeFormatted + " with " + filesToProcess.length + " files. Attempting compression...");
            try {
                // Compresser.compressFile 期望 filesToProcess 中的路徑是相對於 currentBasePath 的
                String compressedArchiveName = Compresser.compressFile(currentBasePath, filesToProcess, "AirShit_Archive");

                if (compressedArchiveName != null) {
                    File archiveFile = new File(currentBasePath, compressedArchiveName);
                    if (archiveFile.exists() && archiveFile.isFile()) {
                        isCompressedSuccessfully = true;
                        filesToSendInLoop = new String[]{compressedArchiveName}; // 更新迴圈中要傳送的檔案列表
                        // loopBasePath 保持為 currentBasePath，因為壓縮檔建立在那裡
                        // currentTotalSizeForCallback 保持為 originalTotalSize，以與 handshake 一致
                        // println("Compression successful. New file to send: " + compressedArchiveName);
                    } else {
                        println("Compression reported success, but archive file not found: " + archiveFile.getAbsolutePath());
                    }
                } else {
                    println("Compression returned null, sending files individually.");
                }
            } catch (IOException e) {
                // isCompressedSuccessfully 保持 false
                callback.onError(new Exception("IOException during compression: " + e.getMessage() + ". Sending files individually.", e));
            }
        }

        callback.onStart(currentTotalSizeForCallback); // 使用與 handshake 一致的 totalSize

        for (String relativeFilePathInLoop : filesToSendInLoop) {
            // relativeFilePathInLoop 是相對於 loopBasePath 的
            // 如果壓縮了，它就是壓縮檔的名稱；如果沒壓縮，它就是原始檔案的相對路徑
            File fileToSend = new File(loopBasePath, relativeFilePathInLoop);

            if (!fileToSend.exists() || !fileToSend.isFile()) {
                callback.onError(new FileNotFoundException("File to send not found or is a directory: " + fileToSend.getAbsolutePath()));
                // 決定是否要因為一個檔案失敗而中止整個傳輸
                return; // 或者 continue; 嘗試傳送下一個
            }

            String fileNameForHeader = relativeFilePathInLoop; // 傳送相對路徑或壓縮檔名
            String fileSizeForHeader = String.valueOf(fileToSend.length());

            try (Socket socket2 = new Socket(host, port);
                 DataOutputStream dos = new DataOutputStream(socket2.getOutputStream());
                 DataInputStream dis = new DataInputStream(socket2.getInputStream())) {

                // 1) send the file-name|size|isCompressed header
                // isCompressedSuccessfully 標記這次傳輸的檔案是否為一個代表多檔案的壓縮包
                dos.writeUTF(fileNameForHeader + "|" + fileSizeForHeader + "|" + isCompressedSuccessfully);
                dos.flush();

                String response = dis.readUTF();
                if (!"ACK".equals(response)) {
                    callback.onError(new IOException("Receiver did not ACK file metadata for: " + fileNameForHeader + ". Response: " + response));
                    return;
                }

                SendFile sendFileTask;
                if (fileToSend.length() < 6 * 1024 * 1024) { // 6MB
                    sendFileTask = new SendFile(host, port, fileToSend, 1, callback);
                } else {
                    sendFileTask = new SendFile(host, port, fileToSend, threadCount, callback);
                }
                sendFileTask.start(); // 假設 SendFile.start() 是阻塞的或內部管理執行緒並等待完成

                response = dis.readUTF(); // 等待 "OK"
                if (!"OK".equals(response)) {
                    callback.onError(new IOException("Receiver failed to confirm reception for: " + fileNameForHeader + ". Response: " + response));
                    return;
                }
            } catch (IOException | InterruptedException e) {
                callback.onError(new IOException("Error during transfer of " + fileNameForHeader + ": " + e.getMessage(), e));
                Thread.currentThread().interrupt(); // 重新設定中斷狀態
                return;
            }
        }

        if (isCompressedSuccessfully && filesToSendInLoop.length == 1) {
            // 刪除本地的壓縮檔案，filesToSendInLoop[0] 是壓縮檔名，loopBasePath 是其所在目錄
            File archiveToDelete = new File(loopBasePath, filesToSendInLoop[0]);
            try {
                Files.deleteIfExists(archiveToDelete.toPath());
                // println("Deleted temporary archive: " + archiveToDelete.getAbsolutePath());
            } catch (IOException e) {
                println("Warning: Failed to delete temporary archive " + archiveToDelete.getAbsolutePath() + ": " + e.getMessage());
            }
        }

        callback.onComplete();
    }


    // 內部 Compresser 類別保持不變，但其呼叫者 (sendFiles) 提供了正確的 basePathForFiles
    private static class Compresser {
        public static String compressFile(String basePathForFiles, String[] relativeFilePaths, String archiveNamePrefix) throws IOException {
            if (relativeFilePaths == null || relativeFilePaths.length == 0) {
                return null;
            }
            // 安全性: 驗證 basePathForFiles 是否為有效且預期的目錄
            Path baseP = Paths.get(basePathForFiles);
            if (!Files.isDirectory(baseP)) {
                throw new IOException("Base path for compression is not a valid directory: " + basePathForFiles);
            }

            String archiveFileName = archiveNamePrefix + System.currentTimeMillis() + ".zip";
            File archiveFile = new File(basePathForFiles, archiveFileName);

            try (FileOutputStream fos = new FileOutputStream(archiveFile);
                 ZipOutputStream zos = new ZipOutputStream(fos)) {
                byte[] buffer = new byte[4096];

                for (String relativePath : relativeFilePaths) {
                    if (relativePath == null || relativePath.trim().isEmpty()) {
                        continue;
                    }
                    // 安全性: 清理 relativePath 並確保它不會跳出 basePathForFiles
                    // 例如: relativePath = sanitizeRelativePath(relativePath);
                    File fileToZip = new File(basePathForFiles, relativePath);
                    Path fileToZipPath = fileToZip.toPath().normalize();

                    // 確保要壓縮的檔案在基礎路徑內 (防止路徑遍歷讀取)
                    if (!fileToZipPath.startsWith(baseP.normalize())) {
                        System.err.println("Path traversal attempt detected for compression, skipping: " + relativePath);
                        continue;
                    }

                    if (!fileToZip.exists() || !fileToZip.isFile()) {
                        System.err.println("File for zipping not found or is not a file, skipping: " + fileToZip.getAbsolutePath());
                        continue;
                    }

                    // ZipEntry 的名稱使用相對路徑
                    // 安全性: 確保 relativePath 用於 ZipEntry 時是安全的 (例如，不以 / 或 ../ 開頭)
                    String entryName = Paths.get(relativePath).normalize().toString();
                    // 移除任何可能的前導斜線，以確保是相對條目
                    if (entryName.startsWith(File.separator) || entryName.startsWith("/")) {
                        entryName = entryName.substring(1);
                    }
                    // 再次檢查 entryName 是否包含 ".."
                    if (entryName.contains("..")) {
                         System.err.println("Invalid entry name for zip (contains '..'), skipping: " + entryName);
                         continue;
                    }


                    ZipEntry zipEntry = new ZipEntry(entryName);
                    zos.putNextEntry(zipEntry);

                    try (FileInputStream fis = new FileInputStream(fileToZip)) {
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }
                    }
                    zos.closeEntry();
                }
                return archiveFileName;
            } catch (IOException e) {
                Files.deleteIfExists(archiveFile.toPath()); // 嘗試刪除部分建立的檔案
                throw e;
            }
        }
    }

    // 輔助方法 (建議):
    // private String sanitizePathSegment(String segment) { ... }
    // private String sanitizeRelativePath(String relativePath) { ... }
}