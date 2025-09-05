package AirShit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.*;
import java.util.HashSet;
import java.util.List;

public class TarCompressor {

    /** 500 MB 閾值 */
    private static final long SIZE_THRESHOLD = 500L * 1024L * 1024L;

    /**
     * 將 inputFile（檔案或資料夾）遞迴打包成 .tar，
     * 並把未打包的大檔案與最終生成的 .tar 加入 resultList。
     *
     * @param inputFile  要打包的檔案或資料夾
     * @param outputFile 打包後的 .tar 檔案
     * @param resultList 傳入一個空的 List<File>，最終會包含未打包的大檔案與生成的 .tar
     */
    public static void packToTar(
            File inputFile,
            File outputFile,
            HashSet<File> resultList
    ) throws IOException {
        // 建立 .tar 檔案輸出串流
        try (FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(bos)
        ) {
            // 支援超長檔名
            tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);

            // 開始遞迴加入檔案
            addFileRecursively(tarOut, inputFile, "", resultList);
        }

        // 把最終的 .tar 檔也加入 resultList
        resultList.add(outputFile);
    }

    private static void addFileRecursively(
            TarArchiveOutputStream tarOut,
            File file,
            String basePath,
            HashSet<File> resultList
    ) throws IOException {
        String entryName = basePath + file.getName();

        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addFileRecursively(tarOut, child, entryName + "/", resultList);
                }
            }
        } else {
            // 檔案小於閾值才打包，否則跳過並加入 resultList
            if (file.length() < SIZE_THRESHOLD) {
                TarArchiveEntry entry = new TarArchiveEntry(file, entryName);
                tarOut.putArchiveEntry(entry);

                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buffer = new byte[8 * 1024];
                    int len;
                    while ((len = bis.read(buffer)) != -1) {
                        tarOut.write(buffer, 0, len);
                    }
                }

                tarOut.closeArchiveEntry();
            } else {
                resultList.add(file);
            }
        }
    }

    /**
     * 範例 main：命令行執行方式
     * java -cp target/your.jar TarOnlyCompressor <inputPath> <output.tar>
     */
    public static void start(File input , HashSet<File> resultList) {
        // if (args.length != 2) {
            // System.err.println("Usage: java TarOnlyCompressor <inputPath> <output.tar>");
            // System.exit(1);
        // }

        File output = new File((input.getName() + ".tar"));
        if (!output.exists()) {
            try {
                output.createNewFile();
            } catch (IOException e) {
                System.err.println("無法建立輸出檔案: " + output.getAbsolutePath());
                e.printStackTrace();
                return;
            }
        }
        try {
            packToTar(input, output, resultList);
            System.out.println("打包完成，以下檔案未打包或已生成：");
            resultList.forEach(f -> System.out.println(" - " + f.getAbsolutePath()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}