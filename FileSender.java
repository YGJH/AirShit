package AirShit;
import java.io.*;
import java.net.Socket;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SendFile: 將檔案分割成多段，並以多執行緒同時傳送給 Receiver。
 */
public class FileSender {
    private  String host;
    private  int port;
    private  String fatherDir; // 父資料夾
    // get Hardware Concurrent

    public FileSender(String host, int port , String fatherDir) { 
        this.host = host;
        this.port = port;
        this.fatherDir = fatherDir;
        // System.out.println("fatherDir: " + fatherDir);
    }
    public void println (String str) {
        System.out.println(str);
    }
    public void sendFiles(String[] files , String SenderUserName , String folderName , TransferCallback callback) throws IOException, InterruptedException {

        // handshake
        StringBuilder sb = new StringBuilder();
        long totalSize = 0;
        int threadCount = Runtime.getRuntime().availableProcessors(); // 硬體執行緒數量
        boolean isSingleFile = files.length == 1;
        // System.out.println("folderName: " + fatherDir+"\\"+folderName+"\\"+files[0]);
        if(isSingleFile) {
            sb.append("isSingle|");
            files[0] = new File(files[0]).getName();
            sb.append(SenderUserName).append("|").append(new File(fatherDir+"\\"+files[0]).getName());
            totalSize = new File(fatherDir+"\\"+files[0]).length();
        } else {
            fatherDir = fatherDir + "\\" + folderName;
            sb.append("isMulti|");
            sb.append(SenderUserName + "|" + folderName);
            for (String filePath : files) {
                File f = new File(fatherDir +"\\"+ filePath);
                totalSize += f.length();
                sb.append("|").append(f.getName());
            }
        }
        sb.append("|").append(totalSize);
        String totalSize2 = SendFileGUI.formatFileSize(totalSize);
        sb.append("|"+totalSize2); // 檔案總大小

        sb.append("|"+(threadCount)); // 硬體執行緒數量
        
        
        // 連線到 Receiver
        try (Socket socket = new Socket(host, port);
        DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
        DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            // 傳送 handshake 訊息
            dos.writeUTF(sb.toString());
            dos.flush();
            // println("傳送 handshake 訊息： " + sb.toString());
            // 等待 Receiver 確認接收檔案
            String response = dis.readUTF();
            
            if (response.startsWith("ACK")) {
                String thread = response.split("\\|")[1];
                int tmp = Integer.parseInt(thread); // 硬體執行緒數量
                threadCount = Math.min(threadCount , tmp); // 硬體執行緒數量
                // println("Receiver 確認接收檔案。");
            } else {
                // System.err.println("Receiver 無法接收檔案，請稍後再試。");
                return;
            }
            
        } catch (IOException e) {
            e.printStackTrace();
            // System.err.println("無法連線到 Receiver：");
            return;
        }
        boolean isCompressed = false;
        if (totalSize < 600 * 1024 && files.length > 1) {
            // println("總大小為 " + totalSize + " bytes 且有 " + files.length + " 個檔案。嘗試壓縮...");
            try {
                // files 陣列中的路徑是相對於 currentBaseDir 的
                String compressedArchiveName = Compresser.compressFile(fatherDir, files, "AirShit_Archive");
                isCompressed = true;
                totalSize = new File(fatherDir+"\\"+compressedArchiveName).length(); // 更新總大小
                if (compressedArchiveName != null) {
                    files = new String[]{compressedArchiveName}; // 更新要傳送的檔案列表為單一壓縮檔
                    // println("壓縮成功。新的傳送檔案: " + compressedArchiveName);
                    // 注意：此時 totalSize 仍然是原始總大小，進度條可能基於此。
                    // 單獨檔案傳輸時會使用壓縮檔的實際大小。
                } else {
                    // System.err.println("壓縮回傳 null，將單獨傳送檔案。");
                }
            } catch (IOException e) {
                callback.onError(new Exception("壓縮過程中發生 IOException: " + e.getMessage() + "。將單獨傳送檔案。"));
            }
        }
        
        callback.onStart(totalSize);
        // System.out.println(files.length + " 個檔案需要傳送。");
        for (String filePath : files) {
            // notify user
            File file = new File(fatherDir+"\\"+filePath);
            String fileName = filePath;
            String fileSize = String.valueOf(file.length());
            try (Socket socket2 = new Socket(host, port);
                DataOutputStream dos = new DataOutputStream(socket2.getOutputStream());
                DataInputStream  dis = new DataInputStream(socket2.getInputStream())) {
                // System.out.println("開始傳送檔案：" + fileName + " size: " + fileSize);
                // 1) send the file‑name|size header
                dos.writeUTF(fileName + "|" + fileSize+"|"+isCompressed);
                dos.flush();
        
                // 2) wait for ACK on the same socket
                String response = dis.readUTF();
                if (!"ACK".equals(response)) {
                    // System.err.println("Receiver 無法接收檔案：" + fileName);
                    return;
                } else {
                    // println("receiver 已開始接收檔案");
                }
                // 3) now kick off your SendFile/ChunkSender against socket2
                SendFile sendFile;
                if(file.length() < 6 * 1024 * 1024) { // 6MB
                    sendFile = new SendFile(
                        host, port, file,1, callback);
                } else {
                    sendFile = new SendFile(
                        host, port, file, threadCount, callback);
                }
                sendFile.start();
                response = dis.readUTF();
                println(response);
                if (!"OK".equals(response)) {
                    // System.err.println("Receiver 無法接收檔案：" + fileName);
                    callback.onError(new IOException("Receiver 無法接收檔案：" + fileName));
                    return;
                } else {
                    // println("receiver 已完成接收檔案");
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
                return;
            }
        }

        if(isCompressed) {
            new File(files[0]).delete(); // 刪除壓縮檔
        }

        // System.out.println("所有檔案傳送完成。");
        callback.onComplete();
    }




    private class Compresser {
        /**
         * 將指定的檔案列表壓縮成一個 ZIP 檔案。
         * @param basePathForFiles 這些檔案所在的基礎目錄路徑。
         * @param relativeFilePaths 要壓縮的檔案相對路徑列表 (相對於 basePathForFiles)。
         * @param archiveNamePrefix 壓縮檔案名稱的前綴。
         * @return 成功則回傳建立的壓縮檔案名稱 (位於 basePathForFiles 中)，失敗則回傳 null 或拋出 IOException。
         * @throws IOException 如果發生 I/O 錯誤。
         */
        public static String compressFile(String basePathForFiles, String[] relativeFilePaths, String archiveNamePrefix) throws IOException {
            if (relativeFilePaths == null || relativeFilePaths.length == 0) {
                System.err.println("沒有檔案可供壓縮。");
                return null;
            }

            String archiveFileName = archiveNamePrefix + "_" + System.currentTimeMillis() + ".zip";
            File archiveFile = new File(basePathForFiles, archiveFileName);

            System.out.println("準備建立壓縮檔案: " + archiveFile.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(archiveFile);
                ZipOutputStream zos = new ZipOutputStream(fos)) {

                byte[] buffer = new byte[4096]; // 4KB 緩衝區

                for (String relativePath : relativeFilePaths) {
                    if (relativePath == null || relativePath.trim().isEmpty()) {
                        continue;
                    }
                    File fileToZip = new File(basePathForFiles, relativePath);

                    if (!fileToZip.exists()) {
                        System.err.println("要壓縮的檔案未找到，跳過: " + fileToZip.getAbsolutePath());
                        continue;
                    }
                    if (fileToZip.isDirectory()) {
                        // 這個簡單版本不遞迴壓縮資料夾內的檔案，如果需要，則要擴展此處邏輯
                        System.err.println("跳過資料夾 (此簡易壓縮功能不支援遞迴壓縮資料夾): " + fileToZip.getAbsolutePath());
                        continue;
                    }

                    // ZipEntry 的名稱使用相對路徑，這樣解壓縮時可以保持結構
                    ZipEntry zipEntry = new ZipEntry(relativePath);
                    zos.putNextEntry(zipEntry);

                    try (FileInputStream fis = new FileInputStream(fileToZip)) {
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }
                    }
                    zos.closeEntry();
                    // println("已加入到壓縮檔 '" + archiveFileName + "': " + relativePath);
                }
                System.out.println("壓縮成功。壓縮檔案已建立: " + archiveFile.getName());
                return archiveFileName; // 回傳檔案名稱，它位於 basePathForFiles 中
            } catch (IOException e) {
                System.err.println("建立 ZIP 檔案 '" + archiveFile.getAbsolutePath() + "' 時發生錯誤: " + e.getMessage());
                if (archiveFile.exists()) {
                    if (archiveFile.delete()) {
                        // println("已刪除部分建立的壓縮檔案: " + archiveFile.getName());
                    } else {
                        System.err.println("刪除部分建立的壓縮檔案失敗: " + archiveFile.getName());
                    }
                }
                throw e; // 重新拋出例外，由呼叫者處理
            }
        }

    }
}