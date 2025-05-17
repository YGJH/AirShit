package AirShit;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

public class FolderSelector {
    // private static String folderName = null;



    // public static String getFolderName() {
    //     return folderName;
    // }
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
    public static File selectFolderOrFiles(Component parentComponent) {
        JFileChooser chooser = new JFileChooser();
        // 選擇目錄
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("請選擇一個資料夾或檔案");

        int result = chooser.showOpenDialog(parentComponent);
        System.out.println(result);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFileOrFolder = chooser.getSelectedFile();
            if(selectedFileOrFolder != null) {
                return selectedFileOrFolder;
            }
        }
        return null; // User cancelled or closed dialog
    }


    /**
     * 列出指定目录下所有文件的相对路径（不包含目录本身）。
     *
     * @param folder 根目录
     * @return 所有子文件的相对路径数组，若输入无效则返回空数组
     */
    public static String[] listFilesRecursivelyWithRelativePaths(File folder) {
        if (folder == null || !folder.exists() || !folder.isDirectory()) {
            return new String[0];
        }

        // 用set存储相对路径，避免重复
        Set<String> relPaths = new HashSet<>();

        Path basePath = folder.toPath();
        collectRelPaths(folder, basePath, relPaths);
        for(File f : folder.listFiles()) {
            if(f.isFile()) {
                relPaths.add(f.getName());
            }
        }
        return relPaths.toArray(new String[0]);
    }

    /**
     * 递归遍历，将每个文件的相对路径加入列表。
     *
     * @param current  当前遍历到的文件或目录
     * @param basePath 根目录的 Path，用于 relativize
     * @param relPaths 用于收集相对路径的列表
     */
    private static void collectRelPaths(File current, Path basePath, Set<String> relPaths) {
        if (current.isFile()) {
            // 计算相对路径
            Path rel = basePath.relativize(current.toPath());
            relPaths.add(rel.toString());
        } else if (current.isDirectory()) {
            File[] children = current.listFiles();
            if (children != null) {
                for (File child : children) {
                    collectRelPaths(child, basePath, relPaths);
                }
            }
        }
    }

    // 範例用法
    // public static void main(String[] args) {
    //     File folder = new File("/path/to/your/folder");
    //     File[] allFiles = listFilesRecursively(folder);
    //     System.out.println("共找到 " + allFiles.length + " 个文件：");
    //     for (File f : allFiles) {
    //         System.out.println(f.getAbsolutePath());
    //     }
    // }
    public static String selectFolder() {
        JFileChooser chooser = new JFileChooser();
        // 只允許選擇目錄
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("請選擇一個資料夾");

        int result = chooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();

            return folder.getAbsolutePath();
        } else {
            // 使用者按了「取消」或關閉視窗
            return null;
        }
    }

}