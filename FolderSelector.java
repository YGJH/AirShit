package AirShit;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FolderSelector {
    private static String folderName = null;
    private static final int fileMaxCount = 100000; // 每個資料夾最多 100000 個檔案
    public static String getFolderName() {
        return folderName;
    }
    public static void println(String str) {
        System.out.println(str);
    }


    /**
     * 顯示一個資料夾選擇對話框，讓使用者選擇資料夾後，
     * 回傳該資料夾底下所有檔案與子資料夾的 List<File>。
     *
     * @param parentComponent 作為對話框的父元件，若為 null 則無父元件
     * @return 選擇的資料夾底下所有檔案／子資料夾的 List，若使用者取消則回傳空 List
     */
    public static File[] selectFolderAndListFiles(Component parentComponent) {
        JFileChooser chooser = new JFileChooser();
        // 選擇目錄
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("請選擇一個資料夾或檔案");

        int result = chooser.showOpenDialog(parentComponent);
        System.out.println(result);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFileOrFolder = chooser.getSelectedFile();
            folderName = selectedFileOrFolder.getAbsolutePath(); // Store the full path

            if(selectedFileOrFolder.isFile()) {
                return new File[]{selectedFileOrFolder};
            }

            // At this point, selectedFileOrFolder is a directory
            File folder = selectedFileOrFolder;

            // Update folderName to be just the name for getFolderName() consistency,
            // but use 'folder' (File object) for operations.
            String nameOnly = folder.getName();
            // The original logic to extract folder name (if needed elsewhere, keep it, but use 'folder' for listing)
            for(int i = folderName.length() - 1; i >= 0; i--) {
                if(folderName.charAt(i) == '\\' 
                        || folderName.charAt(i) == '/') {
                    FolderSelector.folderName = folderName.substring(i + 1 , folderName.length()); // Update static field
                    break;
                }
            }
            if (folderName.isEmpty()) { // if root directory was selected
                FolderSelector.folderName = nameOnly;
            }


            int count = 0;
            File[] files = new File[fileMaxCount];
            // String[] fileList = folderName.list(); // Original error line
            String[] topLevelNames = folder.list(); // Use the File object 'folder'

            if (topLevelNames != null) {
                for(String name : topLevelNames) {
                    if(count >= fileMaxCount) {
                        break;
                    }
                    File currentItem = new File(folder, name); // Construct File object correctly

                    if(currentItem.isFile()) {
                        files[count++] = currentItem;
                    } else if (currentItem.isDirectory()) {
                        files[count++] = currentItem; // Add the directory itself
                        File[] subFiles = getFileAndFolder(currentItem); // Pass the directory File object
                        if(subFiles != null) {
                            for(File subFile : subFiles) {
                                if(count >= fileMaxCount) {
                                    break;
                                }
                                println(currentItem.getName() + "\\" + subFile.getName());
                                files[count++] = new File(currentItem.getName() + "\\" + subFile.getName()); // Add sub-file with path
                            }
                        }
                    }
                    if(count >= fileMaxCount) break;
                }
            }

            if(count > 0) {
                return Arrays.copyOf(files, count); // Return a trimmed array
            } else {
                return null; // Or new File[0] if preferred for empty/cancelled
            }
        }
        return null; // User cancelled or closed dialog
    }

    private static File[] getFileAndFolder(File directory) { // Renamed f to directory
        int count = 0; // Declare and initialize count
        File[] files = new File[fileMaxCount]; // Local array for this directory's content

        if(directory.isDirectory()) {
            // String[] fileList = directory.list(); // Original
            String[] entryNames = directory.list(); // Returns String array of names

            if (entryNames != null) {
                for(String name : entryNames) { // Iterate over String names
                    if (count >= fileMaxCount) break;

                    File currentEntry = new File(directory, name); // Create File object

                    if(currentEntry.isFile()) {
                        files[count++] = currentEntry;
                    } else if (currentEntry.isDirectory()) {
                        files[count++] = currentEntry; // Add the directory itself
                        File[] subFiles = getFileAndFolder(currentEntry); // Recursive call
                        if(subFiles != null) {
                            for(File subFile : subFiles) {
                                if(count >= fileMaxCount) {
                                    break;
                                }
                                files[count++] = subFile;
                            }
                        }
                    }
                    if (count >= fileMaxCount) break;
                }
            }
            if (count > 0) {
                return Arrays.copyOf(files, count); // Return a trimmed array
            }
            return new File[0]; // Return empty array if directory is empty or unreadable after checks
        } else {
            return null; // Not a directory
        }
    }

    public static String selectFolder() {
        JFileChooser chooser = new JFileChooser();
        // 只允許選擇目錄
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("請選擇一個資料夾");

        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();
            folderName = folder.getAbsolutePath();

            return folderName;
        } else {
            // 使用者按了「取消」或關閉視窗
            return null;
        }
    }

}