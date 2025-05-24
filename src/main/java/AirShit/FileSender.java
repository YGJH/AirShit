package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import AirShit.ui.*;
import AirShit.ui.LogPanel;

/**
 * FileSender 類別負責處理將檔案或資料夾傳送給接收端的整個流程。
 * 這包括：
 * 1. 準備要傳送的檔案列表（對於資料夾，可能包括壓縮小檔案到一個 .tar.lz4 存檔）。
 * 2. 與接收端進行多階段握手，交換元數據（檔案總數、總大小、執行緒請求、是否為目錄、原始資料夾名稱）和每個檔案的資訊。
 * 3. 等待接收端的接受或拒絕決定。
 * 4. 如果接受，則為每個要傳送的檔案（可能是原始大檔案或 .tar.lz4 存檔）啟動一個 SendFile 實例進行傳輸。
 * 5. 清理臨時檔案。
 */
public class FileSender {
    private String host; // 接收端主機名稱或 IP
    private int port; // 接收端埠號
    private SendFile senderInstance; // 用於傳送單一檔案資料的 SendFile 實例
    private final int ITHREADS = Runtime.getRuntime().availableProcessors(); // 本機可用的處理器核心數，作為建議的執行緒數
    private final String THREADS_STR = Integer.toString(ITHREADS); // 處理器核心數的字串形式

    private static final int DEFAULT_SOCKET_TIMEOUT_SECONDS = 15; // 預設 Socket 操作超時時間（秒）
    private static final int USER_INTERACTION_TIMEOUT_MINUTES = 5; // 等待使用者（接收端）互動的超時時間（分鐘）
    // private static final int MAX_INITIAL_HANDSHAKE_RETRIES = 3; // 最大初始握手重試次數
    // (目前未使用)

    /**
     * FileSender 的建構函式。
     * 
     * @param host 接收端主機。
     * @param port 接收端埠號。
     */
    public FileSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 輔助方法，用於輸出調試資訊到日誌面板。
     * 
     * @param str 要輸出的字串。
     */
    public static void println(String str) {
        LogPanel.log("DEBUG_PRINTLN: " + str);
    }

    /**
     * 主要方法，用於傳送指定的檔案或資料夾。
     * 
     * @param inputFile      要傳送的檔案或資料夾 (File 物件)。
     * @param senderUserName 傳送端的使用者名稱。
     * @param callback       用於報告傳輸狀態的回呼介面。
     */
    public void sendFiles(File inputFile, String senderUserName, TransferCallback callback) {
        if (inputFile == null) {
            LogPanel.log("FileSender: 輸入的檔案/目錄為 null。中止操作。");
            if (callback != null)
                callback.onError(new IllegalArgumentException("輸入的檔案/目錄不能為 null。"));
            return;
        }

        List<File> filesToProcess = new ArrayList<>(); // 實際需要傳送的檔案列表 (大檔案 + 壓縮檔)
        String tempArchiveFilePath = null; // 如果創建了臨時 .tar.lz4 存檔，其路徑
        boolean isDirectoryTransfer = inputFile.isDirectory(); // 判斷輸入是否為目錄
        long totalSizeOverall = 0; // 所有待傳送檔案的總大小
        Path tempDirForArchive = null; // 儲存存檔的臨時目錄的路徑
        Path baseDirectoryPath = null; // 用於儲存所選資料夾的基礎路徑

        // 最外層的 try-finally 用於確保清理臨時存檔及其目錄（如果創建了的話）
        try {
            LogPanel.log("FileSender: 準備傳送檔案...");
            if (isDirectoryTransfer) { // 如果是傳送資料夾
                baseDirectoryPath = inputFile.toPath(); // 儲存基礎資料夾路徑
                String baseName = inputFile.getName(); // 資料夾的基本名稱
                tempDirForArchive = Files.createTempDirectory("airshit_send_temp_"); // 創建唯一的臨時目錄
                String compressedFileName = baseName + ".tar.lz4"; // 壓縮檔案的名稱
                tempArchiveFilePath = Paths.get(tempDirForArchive.toString(), compressedFileName).toString(); // 壓縮檔案的完整路徑

                // LZ4FileCompressor 會將小於 3MB 的檔案壓縮到 tempArchiveFilePath，
                // 並將大於等於 3MB 的檔案填充到 largeFilesArray。
                File[] largeFilesArray = new File[1000000]; // 假設最多有這麼多大檔案，可根據需要調整
                int largeFileCount = LZ4FileCompressor.compressFolderToTarLz4(inputFile.getAbsolutePath(),
                        tempArchiveFilePath, largeFilesArray);

                // 將所有大檔案加入到待處理列表
                for (int i = 0; i < largeFileCount; i++) {
                    if (largeFilesArray[i] != null && largeFilesArray[i].exists()) {
                        filesToProcess.add(largeFilesArray[i]);
                        totalSizeOverall += largeFilesArray[i].length();
                    }
                }

                File archiveFile = new File(tempArchiveFilePath); // 創建代表壓縮檔的 File 物件
                // 如果壓縮檔存在且有內容，也將其加入待處理列表
                if (archiveFile.exists() && archiveFile.length() > 0) {
                    filesToProcess.add(archiveFile);
                    totalSizeOverall += archiveFile.length();
                } else if (largeFileCount == 0) { // 如果沒有大檔案，且壓縮檔不存在或為空
                    LogPanel.log("FileSender: 目錄 '" + inputFile.getName() + "' 為空或沒有產生任何要傳送的檔案。");
                    if (archiveFile.exists())
                        Files.deleteIfExists(archiveFile.toPath()); // 清理空的壓縮檔
                    // tempDirForArchive 會在最後的 finally 區塊中清理
                    if (callback != null)
                        callback.onComplete(); // 或 onError (如果這算錯誤)
                    return; // 沒有檔案可傳送，直接返回
                }
                LogPanel.log("FileSender: 目錄處理完成。大檔案數量: " + largeFileCount + ". 壓縮檔: "
                        + (archiveFile.exists() && archiveFile.length() > 0 ? archiveFile.getName() : "無"));

            } else { // 如果是傳送單一檔案
                // isCompressed = false; // 舊的標記變數
                filesToProcess.add(inputFile);
                totalSizeOverall += inputFile.length();
                LogPanel.log("FileSender: 選擇了單一檔案: " + inputFile.getName());
            }

            // 再次檢查是否有檔案需要傳送
            if (filesToProcess.isEmpty()) {
                LogPanel.log("FileSender: 準備後沒有檔案需要傳送。");
                if (callback != null)
                    callback.onComplete();
                return;
            }

            LogPanel.log("FileSender: 總共要傳送的檔案數量: " + filesToProcess.size() + ", 總大小: "
                    + SendFileGUI.formatFileSize(totalSizeOverall));

            // ===== 階段 1: 初始握手元數據 =====
            // 格式:
            // SENDER_USERNAME@NUMBER_OF_FILES_TO_SEND@TOTAL_SIZE_BYTES@REQUESTED_THREADS@IS_DIRECTORY@ORIGINAL_FOLDER_NAME
            String originalFolderName = isDirectoryTransfer ? inputFile.getName() : "-"; // 如果是單一檔案，原始資料夾名稱用 "-" 表示
            String initialMetadata = senderUserName + "@" +
                    filesToProcess.size() + "@" +
                    totalSizeOverall + "@" +
                    THREADS_STR + "@" +
                    (isDirectoryTransfer ? "1" : "0") + "@" + // "1" 表示目錄，"0" 表示檔案
                    originalFolderName;

            // 使用 try-with-resources 管理 Socket 和相關的流
            try (Socket socket = new Socket(host, port); // 建立到接收端的 Socket 連接 (用於握手)
                    DataOutputStream dos = new DataOutputStream(socket.getOutputStream()); // 用於向 Socket 寫入資料
                    DataInputStream dis = new DataInputStream(socket.getInputStream())) { // 用於從 Socket 讀取資料

                socket.setSoTimeout(DEFAULT_SOCKET_TIMEOUT_SECONDS * 1000); // 設定 Socket 操作的超時時間

                LogPanel.log("FileSender: 正在傳送初始元數據: " + initialMetadata);
                dos.writeUTF(initialMetadata); // 傳送初始元數據字串
                dos.flush(); // 確保資料被送出

                String ackResponse = dis.readUTF(); // 等待接收端對元數據的確認 (ACK_METADATA)
                if (!"ACK_METADATA".equals(ackResponse)) {
                    throw new IOException("FileSender: 未收到 ACK_METADATA。收到: " + ackResponse);
                }
                LogPanel.log("FileSender: 收到 ACK_METADATA。");

                // ===== 階段 2: 檔案資訊迴圈 =====
                // 為 filesToProcess 中的每個檔案傳送其名稱和大小
                Path selectedPath = inputFile.toPath().normalize(); // 使用者選擇的原始路徑
                Path parentOfSelectedPath = selectedPath.getParent(); // 獲取父目錄
                for (File fileToSendInfo : filesToProcess) {
                    String nameToSend;
                    Path filePath = fileToSendInfo.toPath().normalize();

                    if (isDirectoryTransfer) {
                        if (tempArchiveFilePath != null
                                && filePath.equals(Paths.get(tempArchiveFilePath).normalize())) {
                            // 這是壓縮檔
                            nameToSend = inputFile.getName() + ".tar.lz4"; // 例如 "competitiveShit.tar.lz4"
                        } else {
                            // 這是大檔案
                            if (parentOfSelectedPath != null) {
                                // 相對於父目錄計算，結果會包含 selectedPath 的名稱
                                // e.g., parent="D:\folder", filePath="D:\folder\competitiveShit\.git\pack.idx"
                                // relativePath = "competitiveShit\.git\pack.idx"
                                Path relativePath = parentOfSelectedPath.relativize(filePath);
                                nameToSend = relativePath.toString();
                            } else {
                                // 如果選擇的是根目錄下的資料夾 (e.g., "C:\competitiveShit")
                                // parentOfSelectedPath 會是 "C:\"
                                // 如果選擇的是根目錄本身 (e.g., "C:\"), parentOfSelectedPath 是 null
                                // 這種情況下，我們希望路徑直接從 selectedPath 的名稱開始
                                // filePath 相對於 selectedPath (inputFile.toPath())
                                Path relativeToSelected = selectedPath.relativize(filePath);
                                if (relativeToSelected.toString().isEmpty() && filePath.getFileName().toString()
                                        .equals(selectedPath.getFileName().toString())) {
                                    // 如果檔案就是選擇的目錄本身（理論上大檔案不會是這樣）
                                    nameToSend = selectedPath.getFileName().toString();
                                } else {
                                    nameToSend = selectedPath.getFileName().toString() + File.separator
                                            + relativeToSelected.toString();
                                }
                                // 確保不會出現 "competitiveShit\" + "" 的情況
                                if (nameToSend.endsWith(File.separator) && relativeToSelected.toString().isEmpty()) {
                                    nameToSend = selectedPath.getFileName().toString();
                                }
                            }
                        }
                    } else {
                        // 單一檔案傳輸
                        nameToSend = fileToSendInfo.getName();
                    }
                    System.out.println(nameToSend);
                    String fileInfoString = nameToSend + "@" + fileToSendInfo.length();
                    // System.out.println(fileToSendInfo.getName()); // 舊的調試輸出
                    LogPanel.log("FileSender: 正在傳送檔案資訊: " + fileInfoString);
                    dos.writeUTF(fileInfoString);
                    dos.flush();
                    String fileInfoAck = dis.readUTF(); // 等待接收端對此檔案資訊的確認 (ACK_FILE_INFO)
                    if (!"ACK_FILE_INFO".equals(fileInfoAck)) {
                        throw new IOException("FileSender: 未收到檔案 " + fileToSendInfo.getName() + " 的 ACK_FILE_INFO。收到: "
                                + fileInfoAck);
                    }
                    LogPanel.log("FileSender: 收到檔案 " + fileToSendInfo.getName() + " 的 ACK_FILE_INFO。");
                }

                // ===== 階段 3: 等待接收方決定 =====
                // 等待接收端的決定 (OK@協商後的執行緒數 或 REJECT)
                int originalTimeoutMillis = socket.getSoTimeout(); // 保存原始超時設定
                socket.setSoTimeout(USER_INTERACTION_TIMEOUT_MINUTES * 60 * 1000); // 設定較長的超時以等待使用者操作
                LogPanel.log("FileSender: 設定超時為 " + USER_INTERACTION_TIMEOUT_MINUTES + " 分鐘，等待接收端決定 (OK@/REJECT)。");
                String receiverDecision;
                try {
                    receiverDecision = dis.readUTF(); // 讀取接收端的決定
                } finally {
                    socket.setSoTimeout(originalTimeoutMillis); // 恢復原始超時設定
                    LogPanel.log("FileSender: 超時已恢復至 " + originalTimeoutMillis / 1000 + " 秒。");
                }
                LogPanel.log("FileSender: 收到來自接收端的決定: " + receiverDecision);

                int negotiatedThreadCount = 1; // 協商後的執行緒數量，預設為1
                boolean transferAcceptedByReceiver = false; // 標記傳輸是否被接收端接受

                if (receiverDecision.startsWith("OK@")) { // 如果接收端接受
                    try {
                        negotiatedThreadCount = Integer.parseInt(receiverDecision.substring(3)); // 解析協商後的執行緒數
                        // 確保執行緒數在合理範圍內 (不超過本機核心數，且至少為1)
                        negotiatedThreadCount = Math.min(ITHREADS, Math.max(1, negotiatedThreadCount));
                        LogPanel.log("FileSender: 傳輸被接收端接受。協商後的執行緒數: " + negotiatedThreadCount);
                        dos.writeUTF("ACK_DECISION"); // 傳送端確認收到 OK 決定
                        dos.flush();
                        transferAcceptedByReceiver = true;
                    } catch (NumberFormatException | IndexOutOfBoundsException e) {
                        throw new IOException("FileSender: 接收端 OK 訊息格式無效: " + receiverDecision, e);
                    }
                } else if ("REJECT".equals(receiverDecision)) { // 如果接收端拒絕
                    LogPanel.log("FileSender: 傳輸被接收端拒絕。");
                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(
                                null,
                                "傳輸被接收端拒絕。",
                                "傳輸失敗",
                                JOptionPane.WARNING_MESSAGE);
                    });
                    if (callback != null)
                        callback.onError(new IOException("傳輸被接收端拒絕。"));
                } else { // 未知的決定
                    throw new IOException("FileSender: 收到來自接收端的未知決定: " + receiverDecision);
                }
                System.out.println("negotiatedThreadCount: " + negotiatedThreadCount);
                // ===== 階段 4: 資料傳輸 (如果接受) =====
                if (transferAcceptedByReceiver) {
                    if (callback != null)
                        callback.onStart(totalSizeOverall); // 通知 UI 傳輸開始，並傳遞總大小

                    // 遍歷 filesToProcess 列表，為每個檔案啟動 SendFile 實例進行傳輸
                    for (File fileToActuallySend : filesToProcess) {
                        String displayName;
                        if (isDirectoryTransfer && baseDirectoryPath != null) {
                            Path filePath = fileToActuallySend.toPath();
                            if (tempArchiveFilePath != null && filePath.equals(Paths.get(tempArchiveFilePath))) {
                                displayName = fileToActuallySend.getName(); // 壓縮檔
                            } else {
                                displayName = baseDirectoryPath.relativize(filePath).toString();
                            }
                        } else {
                            displayName = fileToActuallySend.getName();
                        }
                        LogPanel.log("FileSender: 開始傳輸檔案: " + displayName + " ("
                                + SendFileGUI.formatFileSize(fileToActuallySend.length()) + ")");

                        // 創建 SendFile 實例，傳入協商後的執行緒數
                        senderInstance = new SendFile(this.host, this.port, fileToActuallySend, negotiatedThreadCount,
                                callback);

                        // 在新執行緒中運行 SendFile 操作以保持 UI 回應性，但等待其完成後再處理下一個檔案。
                        // 若要實現真正的並行檔案傳輸，SendFile 和 Receiver 需要重大重新設計。
                        final File currentFileForThread = fileToActuallySend; // Lambda 表達式中使用的變數需為 final 或 effectively
                                                                              // final
                        final String finalDisplayName = displayName; // 用於日誌
                        Thread senderOperationThread = new Thread(() -> {
                            try {
                                senderInstance.start(); // 此方法會阻塞直到該檔案傳送完成或出錯
                            } catch (Exception e) {
                                LogPanel.log("FileSender: 檔案 " + finalDisplayName + " 的 SendFile 操作發生異常: "
                                        + e.getClass().getSimpleName() + " - " + e.getMessage());
                                if (callback != null) {
                                    // 多次呼叫 onError 比較棘手。考慮一個整體的失敗。
                                    // 目前，讓 SendFile 內部的錯誤由其自身的回呼處理。
                                }
                            }
                        });
                        senderOperationThread.setName("FileSender-Op-" + finalDisplayName); // 設定執行緒名稱
                        senderOperationThread.start(); // 啟動執行緒
                        senderOperationThread.join(); // 等待此檔案的傳輸執行緒完成

                        LogPanel.log("FileSender: 完成檔案 " + finalDisplayName + " 的 SendFile 操作。");
                    }
                    LogPanel.log("FileSender: 所有檔案已處理完畢，準備傳送。");
                    if (callback != null)
                        callback.onComplete(); // 所有檔案傳輸完成後，呼叫 onComplete
                }
            } // Socket, dos, dis 的 try-with-resources 區塊結束，它們會自動關閉
        } catch (IOException e) { // 捕獲 I/O 異常 (例如 Socket 連接失敗、讀寫錯誤等)
            LogPanel.log("FileSender: sendFiles 過程中發生 IOException: " + e.getClass().getSimpleName() + " - "
                    + e.getMessage());
            if (callback != null)
                callback.onError(e);
        } catch (InterruptedException e) { // 捕獲中斷異常 (例如 senderOperationThread.join() 被中斷)
            Thread.currentThread().interrupt(); // 重設中斷狀態
            LogPanel.log("FileSender: sendFiles 被中斷: " + e.getMessage());
            if (callback != null)
                callback.onError(e);
        } finally {
            // 最後的清理工作：刪除臨時的壓縮檔及其目錄
            if (isDirectoryTransfer && tempArchiveFilePath != null) {
                LogPanel.log("FileSender: 最後清理：嘗試刪除臨時壓縮檔: " + tempArchiveFilePath);
                try {
                    Path archivePath = Paths.get(tempArchiveFilePath);

                    Files.deleteIfExists(archivePath); // 刪除壓縮檔
                    LogPanel.log("FileSender: 最後清理：已嘗試刪除壓縮檔 " + archivePath.getFileName());

                    if (tempDirForArchive != null && Files.exists(tempDirForArchive)) {
                        Files.deleteIfExists(tempDirForArchive); // 刪除臨時目錄
                        LogPanel.log("FileSender: 最後清理：已嘗試刪除臨時目錄: " + tempDirForArchive);
                    }
                } catch (IOException ex) {
                    LogPanel.log("FileSender: 最後清理：刪除臨時壓縮檔/目錄時出錯: " + ex.getMessage());
                }
            }
        }
    }
    // 舊的 isCompressed 變數，在新採用 List<File> 的方法後並非嚴格需要
}