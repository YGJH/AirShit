package AirShit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.io.IOUtils;

public class LZ4FileCompressor {

    private static final long LARGE_FILE_THRESHOLD = 2 * 1024 * 1024 * 1024; // 2GB
    private static final int TAR_BUFFER_SIZE = 8192;
    private static final int QUEUE_CAPACITY = 100; // Capacity for the blocking queue

    // Helper class to pass data to TarWriterThread
    private static class TarEntryData {
        final File file; // Source file, null for directory entries not directly from a File object or for sentinel
        final String entryName;
        final boolean isDirectory;
        final boolean isSentinel; // Signal to stop

        // Constructor for files/directories
        public TarEntryData(File file, String entryName, boolean isDirectory) {
            this.file = file;
            this.entryName = entryName;
            this.isDirectory = isDirectory;
            this.isSentinel = false;
        }

        // Constructor for sentinel
        private TarEntryData() {
            this.file = null;
            this.entryName = null;
            this.isDirectory = false;
            this.isSentinel = true;
        }

        public static TarEntryData sentinel() {
            return new TarEntryData();
        }
    }

    // Thread to write entries to TarArchiveOutputStream
    private static class TarWriterThread extends Thread {
        private final TarArchiveOutputStream taros;
        private final BlockingQueue<TarEntryData> queue;
        private volatile IOException exception = null;

        public TarWriterThread(TarArchiveOutputStream taros, BlockingQueue<TarEntryData> queue) {
            this.taros = taros;
            this.queue = queue;
            this.setName("TarWriterThread");
        }

        @Override
        public void run() {
            try {
                while (true) {
                    TarEntryData data = queue.take(); // Blocks if queue is empty
                    if (data.isSentinel) {
                        break; // End of processing
                    }

                    TarArchiveEntry entry;
                    if (data.isDirectory) {
                        entry = new TarArchiveEntry(data.file, data.entryName);
                        taros.putArchiveEntry(entry);
                        taros.closeArchiveEntry();
                    } else { // Is a file
                        entry = new TarArchiveEntry(data.file, data.entryName);
                        taros.putArchiveEntry(entry);
                        try (FileInputStream fis = new FileInputStream(data.file)) {
                            IOUtils.copy(fis, taros, TAR_BUFFER_SIZE);
                        }
                        taros.closeArchiveEntry();
                    }
                }
            } catch (IOException e) {
                this.exception = e;
                System.err.println("Error in TarWriterThread: " + e.getMessage());
                // e.printStackTrace(); // Main thread will print stack trace
            } catch (InterruptedException e) {
                System.err.println("TarWriterThread interrupted: " + e.getMessage());
                Thread.currentThread().interrupt(); // Preserve interrupt status
                this.exception = new IOException("TarWriterThread was interrupted", e);
            }
        }
        
        public IOException getException() {
            return exception;
        }
    }

    // Task to scan folders
    private static class FolderScannerTask implements Runnable {
        private final File currentDir;
        private final String baseArchiveDirPath; // Absolute path of the source folder being archived.
        private final BlockingQueue<TarEntryData> queue;
        private final List<File> largeFilesList; // Synchronized list
        private final ExecutorService executor;

        public FolderScannerTask(File currentDir, String baseArchiveDirPath,
                                 BlockingQueue<TarEntryData> queue, List<File> largeFilesList,
                                 ExecutorService executor) {
            this.currentDir = currentDir;
            this.baseArchiveDirPath = baseArchiveDirPath;
            this.queue = queue;
            this.largeFilesList = largeFilesList;
            this.executor = executor;
        }

        @Override
        public void run() {
            try {
                Path rootArchiveSourcePath = Paths.get(baseArchiveDirPath);
                Path relativizationBase = rootArchiveSourcePath.getParent();

                File[] items = currentDir.listFiles();
                if (items == null) return;

                for (File item : items) {
                    if (Thread.currentThread().isInterrupted()) { // Check for interruption
                        System.err.println("FolderScannerTask for " + currentDir.getPath() + " detected interruption, stopping.");
                        return;
                    }
                    Path itemPath = item.toPath();
                    String entryName;

                    if (relativizationBase != null) {
                        entryName = relativizationBase.relativize(itemPath).toString();
                    } else { // sourceFolder is a root like "C:\\"
                        entryName = rootArchiveSourcePath.relativize(itemPath).toString();
                    }
                    entryName = entryName.replace(File.separatorChar, '/');

                    if (item.isDirectory()) {
                        String dirEntryName = entryName.endsWith("/") ? entryName : entryName + "/";
                        queue.put(new TarEntryData(item, dirEntryName, true));
                        if (!executor.isShutdown()) {
                           executor.submit(new FolderScannerTask(item, baseArchiveDirPath, queue, largeFilesList, executor));
                        }
                    } else if (item.isFile()) {
                        if (item.length() > LARGE_FILE_THRESHOLD) {
                            // largeFilesList is a synchronized list, direct add is thread-safe
                            largeFilesList.add(item);
                        } else {
                            queue.put(new TarEntryData(item, entryName, false));
                        }
                    }
                }
            } catch (InterruptedException e) {
                System.err.println("FolderScannerTask for " + currentDir.getPath() + " interrupted: " + e.getMessage());
                Thread.currentThread().interrupt(); 
            } catch (Exception e) { 
                 System.err.println("Error in FolderScannerTask for " + currentDir.getPath() + ": " + e.getMessage());
                 // e.printStackTrace(); // Avoid excessive logging from worker threads, main thread handles overall error
                 // To propagate this error, the task could return a Future<Boolean> or similar,
                 // or set a shared volatile error flag. For now, logging it.
                 // If queue.put fails due to InterruptedException from writer thread dying, this task will also terminate.
            }
        }
    }

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

        List<File> largeFilesList = Collections.synchronizedList(new ArrayList<>());
        BlockingQueue<TarEntryData> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2); // Conservative thread count
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        
        int filesArrayPopulatedCount = 0;

        try (FileOutputStream fos = new FileOutputStream(outputTarLz4FilePath);
             LZ4FrameOutputStream lz4os = new LZ4FrameOutputStream(fos, BLOCKSIZE.SIZE_64KB);
             TarArchiveOutputStream taros = new TarArchiveOutputStream(lz4os)) {

            taros.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);

            TarWriterThread writerThread = new TarWriterThread(taros, queue);
            writerThread.start();

            // Add the root source folder itself as the first entry to the TAR
            Path sourceFolderPathObj = sourceFolder.toPath();
            Path parentOfSourceFolder = sourceFolderPathObj.getParent();
            String rootEntryName;

            if (parentOfSourceFolder != null) {
                rootEntryName = parentOfSourceFolder.relativize(sourceFolderPathObj).toString();
            } else { // sourceFolder is a root like "C:\\"
                // For a root source folder, its name in the archive is just its own name.
                // e.g. if source is "D:", entry is "D/" (or whatever File#getName returns for a root)
                // If source is "/", entry is "/" (or name of root)
                // TarArchiveEntry usually expects relative paths.
                // Let's use the folder's name.
                String name = sourceFolder.getName();
                if (name.isEmpty() && sourceFolderPathObj.getNameCount() == 0) { // e.g. "C:\" -> getName() is "", path name count is 0
                    name = sourceFolderPathObj.toString().replace(File.separatorChar, '/'); // Use "C:/" or "/"
                     if (name.endsWith("/") && name.length() > 1) name = name.substring(0, name.length() -1); // Avoid "C://", prefer "C:" then add "/"
                }
                 rootEntryName = name;
            }
            rootEntryName = rootEntryName.replace(File.separatorChar, '/');
            if (!rootEntryName.endsWith("/")) {
                rootEntryName += "/";
            }
            queue.put(new TarEntryData(sourceFolder, rootEntryName, true));

            // Submit initial task for the source folder's contents
            executor.submit(new FolderScannerTask(sourceFolder, sourceFolder.getAbsolutePath(), queue, largeFilesList, executor));
            
            executor.shutdown(); // No new tasks will be accepted
            try {
                // Wait for all scanning tasks to complete or timeout
                if (!executor.awaitTermination(1, TimeUnit.HOURS)) { // Generous timeout
                    System.err.println("Timeout waiting for folder scan to complete. Forcing shutdown.");
                    executor.shutdownNow(); // Attempt to stop all actively executing tasks
                    // Wait a bit for tasks to respond to interruption
                    if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                        System.err.println("Executor did not terminate after force shutdown.");
                    }
                    throw new IOException("Compression timed out during folder scan.");
                }
            } catch (InterruptedException e) {
                System.err.println("Interrupted while waiting for folder scan completion.");
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IOException("Compression interrupted during folder scan.", e);
            }

            // All scanning tasks are done, or timed out/interrupted.
            // Tell TarWriterThread to finish processing whatever is in the queue.
            queue.put(TarEntryData.sentinel());
            writerThread.join(60000); // Wait for writer thread to finish, with a timeout

            if (writerThread.isAlive()) {
                System.err.println("TarWriterThread did not finish in time. Interrupting.");
                writerThread.interrupt();
                writerThread.join(5000); // Wait a bit more
                 if (writerThread.isAlive()) System.err.println("TarWriterThread still alive after interrupt.");
            }
            
            if (writerThread.getException() != null) {
                throw writerThread.getException();
            }
            // taros, lz4os, fos will be closed by try-with-resources

            // Populate filesArray: large files first, then the archive file.
            int currentIdx = 0;
            for (File largeFile : largeFilesList) {
                if (currentIdx < filesArray.length) {
                    filesArray[currentIdx++] = largeFile;
                } else {
                    System.err.println("錯誤：filesArray 陣列空間不足以加入大型檔案: " + largeFile.getName() + ". Skipping remaining large files.");
                    break; 
                }
            }
            
            File archiveFile = new File(outputTarLz4FilePath);
            if (archiveFile.exists() && archiveFile.length() > 0) { // Ensure archive file was created and is not empty
                if (currentIdx < filesArray.length) {
                    filesArray[currentIdx++] = archiveFile;
                } else {
                    System.err.println("錯誤：filesArray 陣列空間不足以加入壓縮檔案本身 (" + outputTarLz4FilePath + ")。");
                }
            } else {
                 System.err.println("警告：壓縮檔案 " + outputTarLz4FilePath + " 未創建或為空，將不會加入到 filesArray。");
            }
            filesArrayPopulatedCount = currentIdx;
            return filesArrayPopulatedCount;

        } catch (IOException e) {
            System.err.println("壓縮資料夾 " + sourceFolderPath + " 時發生錯誤：" + e.getMessage());
            e.printStackTrace();
            cleanupPartialFile(outputTarLz4FilePath, executor);
            return 0; 
        } catch (InterruptedException e) { // From queue.put or writerThread.join
            System.err.println("壓縮資料夾 " + sourceFolderPath + " 時被中斷：" + e.getMessage());
            e.printStackTrace();
            Thread.currentThread().interrupt();
            cleanupPartialFile(outputTarLz4FilePath, executor);
            return 0;
        } finally {
            // Ensure executor is shutdown if not already
            if (executor != null && !executor.isTerminated()) {
                executor.shutdownNow();
            }
        }
    }
    
    private static void cleanupPartialFile(String outputFilePath, ExecutorService executor) {
        if (executor != null && !executor.isTerminated()) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    System.err.println("Executor did not terminate cleanly during cleanup.");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        File partialOutputFile = new File(outputFilePath);
        if (partialOutputFile.exists()) {
            if (!partialOutputFile.delete()) {
                 System.err.println("Warning: Could not delete partial output file: " + outputFilePath);
            }
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
                    if (!file.delete()) {
                        System.err.println("Warning: Could not delete file " + file.getAbsolutePath());
                    }
                }
            }
        }
        if (!directory.delete()) {
            System.err.println("Warning: Could not delete directory " + directory.getAbsolutePath());
        }
    }
}