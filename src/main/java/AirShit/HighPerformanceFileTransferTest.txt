package AirShit;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 高效能檔案傳輸測試程式
 * 使用 Zero Copy + Virtual Threads
 */
public class HighPerformanceFileTransferTest {
    
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("用法: java HighPerformanceFileTransferTest <mode> <file_path> <threads>");
            System.out.println("mode: send 或 receive");
            System.out.println("範例:");
            System.out.println("  發送: java HighPerformanceFileTransferTest send C:\\test\\bigfile.bin 8");
            System.out.println("  接收: java HighPerformanceFileTransferTest receive C:\\test\\received.bin 8");
            return;
        }
        
        String mode = args[0];
        String filePath = args[1];
        int threads = Integer.parseInt(args[2]);
        
        if ("send".equals(mode)) {
            testSender(filePath, threads);
        } else if ("receive".equals(mode)) {
            testReceiver(filePath, threads);
        } else {
            System.out.println("未知模式: " + mode);
        }
    }
    
    public static void testSender(String filePath, int threads) {
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("檔案不存在: " + filePath);
            return;
        }
        
        System.out.println("=== 高效能檔案傳送測試 ===");
        System.out.println("檔案: " + file.getName());
        System.out.println("大小: " + (file.length() / 1024 / 1024) + " MB");
        System.out.println("執行緒數: " + threads);
        System.out.println("CPU 核心數: " + Runtime.getRuntime().availableProcessors());
        
        AtomicLong totalBytes = new AtomicLong(0);
        long startTime = System.currentTimeMillis();
        
        TransferCallback callback = new TransferCallback() {
            private long lastReport = 0;
            
            @Override
            public void onStart(long totalSize) {
                System.out.println("開始傳送: " + totalSize + " bytes");
            }
            
            @Override
            public void onProgress(long bytes) {
                long current = totalBytes.addAndGet(bytes);
                long now = System.currentTimeMillis();
                
                // 每秒報告一次進度
                if (now - lastReport > 1000) {
                    double progress = (double) current / file.length() * 100;
                    double speed = (double) current / (now - startTime) * 1000 / 1024 / 1024; // MB/s
                    
                    System.out.printf("進度: %.1f%% (%.2f MB/s)%n", progress, speed);
                    lastReport = now;
                }
            }
            
            @Override
            public void onComplete() {
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;
                double speed = (double) file.length() / duration * 1000 / 1024 / 1024;
                
                System.out.println("\n=== 傳送完成 ===");
                System.out.println("耗時: " + duration + " ms");
                System.out.printf("平均速度: %.2f MB/s%n", speed);
                System.out.println("總共傳送: " + totalBytes.get() + " bytes");
            }
            
            @Override
            public void onComplete(String name) {
                onComplete();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("傳送錯誤: " + e.getMessage());
                e.printStackTrace();
            }
        };
        
        try {
            SendFileOptimized sender = new SendFileOptimized("localhost", 12345, file, threads, callback);
            sender.start();
        } catch (Exception e) {
            System.err.println("傳送失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static void testReceiver(String outputPath, int threads) {
        System.out.println("=== 高效能檔案接收測試 ===");
        System.out.println("輸出檔案: " + outputPath);
        System.out.println("執行緒數: " + threads);
        System.out.println("CPU 核心數: " + Runtime.getRuntime().availableProcessors());
        System.out.println("等待連接...");
        
        AtomicLong totalBytes = new AtomicLong(0);
        long[] startTime = {0};
        
        TransferCallback callback = new TransferCallback() {
            private long lastReport = 0;
            private long expectedSize = 0;
            
            @Override
            public void onStart(long totalSize) {
                expectedSize = totalSize;
                startTime[0] = System.currentTimeMillis();
                System.out.println("開始接收: " + totalSize + " bytes (" + 
                                 (totalSize / 1024 / 1024) + " MB)");
            }
            
            @Override
            public void onProgress(long bytes) {
                long current = totalBytes.addAndGet(bytes);
                long now = System.currentTimeMillis();
                
                // 每秒報告一次進度
                if (now - lastReport > 1000 && startTime[0] > 0) {
                    double progress = (double) current / expectedSize * 100;
                    double speed = (double) current / (now - startTime[0]) * 1000 / 1024 / 1024;
                    
                    System.out.printf("進度: %.1f%% (%.2f MB/s)%n", progress, speed);
                    lastReport = now;
                }
            }
            
            @Override
            public void onComplete() {
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime[0];
                double speed = (double) totalBytes.get() / duration * 1000 / 1024 / 1024;
                
                System.out.println("\n=== 接收完成 ===");
                System.out.println("耗時: " + duration + " ms");
                System.out.printf("平均速度: %.2f MB/s%n", speed);
                System.out.println("總共接收: " + totalBytes.get() + " bytes");
                
                // 驗證檔案
                File receivedFile = new File(outputPath);
                if (receivedFile.exists()) {
                    System.out.println("檔案大小: " + receivedFile.length() + " bytes");
                }
            }
            
            @Override
            public void onComplete(String name) {
                onComplete();
            }
            
            @Override
            public void onError(Exception e) {
                System.err.println("接收錯誤: " + e.getMessage());
                e.printStackTrace();
            }
        };
        
        try (ServerSocket serverSocket = new ServerSocket(12345)) {
            System.out.println("伺服器啟動，監聽埠 12345");
            
            ReceiverOptimized receiver = new ReceiverOptimized(serverSocket);
            
            // 假設檔案大小 (實際應用中應該透過握手協定獲得)
            long expectedFileSize = 100L * 1024 * 1024; // 100MB 預設值
            
            receiver.start(outputPath, expectedFileSize, threads, callback);
            
        } catch (Exception e) {
            System.err.println("接收失敗: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 建立測試檔案
     */
    public static void createTestFile(String filePath, long sizeInMB) {
        System.out.println("建立測試檔案: " + filePath + " (" + sizeInMB + " MB)");
        
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(filePath, "rw")) {
            raf.setLength(sizeInMB * 1024 * 1024);
            
            // 填入一些測試資料
            byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
            for (int i = 0; i < buffer.length; i++) {
                buffer[i] = (byte) (i % 256);
            }
            
            for (long i = 0; i < sizeInMB; i++) {
                raf.write(buffer);
            }
            
            System.out.println("測試檔案建立完成");
            
        } catch (IOException e) {
            System.err.println("建立測試檔案失敗: " + e.getMessage());
        }
    }
}
