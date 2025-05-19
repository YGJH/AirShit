package AirShit;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

public class GenerateTestFolder {

    private static final String ROOT_DIR_NAME = "GeneratedTestFiles";
    private static final Random random = new Random();
    private static final long KB = 1024L;
    private static final long MB = KB * 1024L;
    private static final long GB = MB * 1024L;

    // Threshold for using RandomAccessFile.setLength() for faster creation of large files
    // Files larger than this will have their size set but might not contain actual random data throughout.
    private static final long FAST_CREATE_THRESHOLD_BYTES = 100 * MB;

    public static void main(String[] args) {
        Path rootPath = Paths.get(ROOT_DIR_NAME);
        try {
            System.out.println("Creating test folder structure in: " + rootPath.toAbsolutePath());
            Files.createDirectories(rootPath);

            // 1. Create various top-level files
            System.out.println("\nCreating top-level files...");
            createFile(rootPath.resolve("file_1_byte.dat"), 1L);
            createFile(rootPath.resolve("file_1KB.dat"), 1L * KB);
            createFile(rootPath.resolve("file_1MB.dat"), 1L * MB);
            createFile(rootPath.resolve("file_10MB_with_content.dat"), 10L * MB); // Content up to FAST_CREATE_THRESHOLD_BYTES
            createFile(rootPath.resolve("file_100MB_with_content.dat"), 100L * MB); // Content up to FAST_CREATE_THRESHOLD_BYTES
            createFile(rootPath.resolve("file_1GB_fast.dat"), 1L * GB);   // Fast creation
            // createFile(rootPath.resolve("file_8GB_fast.dat"), 8L * GB); // Fast creation, uncomment if needed and have space

            // 2. Create subfolders with many small files (total < 600KB)
            System.out.println("\nCreating subfolders with many small files (total < 600KB each)...");
            for (int i = 1; i <= 3; i++) {
                Path smallSubDir = rootPath.resolve("small_files_subdir_" + i);
                Files.createDirectories(smallSubDir);
                populateSubfolderManySmallFiles(smallSubDir, "small_file_", 200, 550 * KB); // Aim for ~550KB total
                System.out.println("Created: " + smallSubDir.getFileName());
            }

            // 3. Create subfolders with files (total >= 600KB)
            System.out.println("\nCreating subfolders with total size >= 600KB each...");
            for (int i = 1; i <= 3; i++) {
                Path largeSubDir = rootPath.resolve("large_content_subdir_" + i);
                Files.createDirectories(largeSubDir);
                populateSubfolderMinSize(largeSubDir, "content_file_", 5, 700 * KB); // Aim for ~700KB total
                System.out.println("Created: " + largeSubDir.getFileName());
            }

            System.out.println("\nTest folder generation complete: " + rootPath.toAbsolutePath());

        } catch (IOException e) {
            System.err.println("An error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void createFile(Path filePath, long sizeInBytes) throws IOException {
        if (Files.exists(filePath)) {
            System.out.println("File already exists, skipping: " + filePath);
            return;
        }
        System.out.print("  Creating " + filePath.getFileName() + " (" + formatSize(sizeInBytes) + ")... ");
        if (sizeInBytes > FAST_CREATE_THRESHOLD_BYTES) {
            // For very large files, use setLength for speed.
            // This creates a file of the specified size, but the content might be zeros
            // or it might be a sparse file depending on the OS and file system.
            try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "rw")) {
                raf.setLength(sizeInBytes);
            }
            System.out.println("Done (fast creation).");
        } else {
            // For smaller files, write actual random content.
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(filePath))) {
                byte[] buffer = new byte[8192]; // 8KB buffer
                long bytesWritten = 0;
                while (bytesWritten < sizeInBytes) {
                    random.nextBytes(buffer);
                    long toWrite = Math.min(buffer.length, sizeInBytes - bytesWritten);
                    os.write(buffer, 0, (int) toWrite);
                    bytesWritten += toWrite;
                }
            }
            System.out.println("Done (with content).");
        }
    }

    private static void populateSubfolderManySmallFiles(Path subfolderPath, String filePrefix, int numFiles, long maxTotalSize) throws IOException {
        long currentTotalSize = 0;
        int maxFileSize = (int) (maxTotalSize / numFiles > 0 ? Math.min(50 * KB, maxTotalSize / numFiles * 2) : 100); // Heuristic for individual file size
        if (maxFileSize <=0) maxFileSize = 100; // ensure positive

        for (int i = 0; i < numFiles; i++) {
            long remainingAllowedSize = maxTotalSize - currentTotalSize;
            if (remainingAllowedSize <= 0) break;

            // Ensure individual files are relatively small
            long fileSize = 1 + random.nextInt((int)Math.min(maxFileSize, remainingAllowedSize));
            if (fileSize <=0) fileSize = 1;


            Path filePath = subfolderPath.resolve(filePrefix + i + ".txt");
            createFile(filePath, fileSize);
            currentTotalSize += fileSize;
            if (currentTotalSize >= maxTotalSize) {
                break;
            }
        }
        System.out.println("  " + subfolderPath.getFileName() + " total size: " + formatSize(currentTotalSize));
    }

    private static void populateSubfolderMinSize(Path subfolderPath, String filePrefix, int numFiles, long minTotalSize) throws IOException {
        long currentTotalSize = 0;
        long targetFileSize = minTotalSize / numFiles;
        if (targetFileSize <= 0) targetFileSize = 1 * KB; // Default if numFiles is too large for minTotalSize

        for (int i = 0; i < numFiles; i++) {
            // Add some randomness, ensure we meet the target
            long fileSizeDeviation = targetFileSize > 100 ? random.nextInt((int)targetFileSize / 2) - ((int)targetFileSize / 4) : 0;
            long fileSize = Math.max(1, targetFileSize + fileSizeDeviation);

            // If this is the last file, adjust its size to meet or exceed minTotalSize
            if (i == numFiles - 1 && currentTotalSize + fileSize < minTotalSize) {
                fileSize = Math.max(1, minTotalSize - currentTotalSize);
            }
            if (fileSize <=0) fileSize = 1;

            Path filePath = subfolderPath.resolve(filePrefix + i + ".dat");
            createFile(filePath, fileSize);
            currentTotalSize += fileSize;
        }
         // If somehow still below, add one more small file
        if (currentTotalSize < minTotalSize) {
            createFile(subfolderPath.resolve(filePrefix + numFiles + ".dat"), minTotalSize - currentTotalSize);
            currentTotalSize = minTotalSize;
        }
        System.out.println("  " + subfolderPath.getFileName() + " total size: " + formatSize(currentTotalSize));
    }

    private static String formatSize(long size) {
        if (size < KB) return size + " B";
        if (size < MB) return String.format("%.2f KB", (double) size / KB);
        if (size < GB) return String.format("%.2f MB", (double) size / MB);
        return String.format("%.2f GB", (double) size / GB);
    }
}