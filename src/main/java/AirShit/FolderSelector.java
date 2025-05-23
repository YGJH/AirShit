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
        chooser.setDialogTitle("請選擇一個檔案或資料夾");

        // --- 建議加入或修改的設定 ---
        // 1. 設定預設開啟的目錄 (例如：使用者的桌面或文件資料夾)
        File defaultDirectory = new File(System.getProperty("user.home") + File.separator + "Desktop");
        if (!defaultDirectory.exists() || !defaultDirectory.isDirectory()) {
            defaultDirectory = new File(System.getProperty("user.home")); // 若桌面不存在，則退回使用者家目錄
        }
        chooser.setCurrentDirectory(defaultDirectory);

        // 2. 設定「開啟」按鈕的文字，使其更明確
        chooser.setApproveButtonText("選擇此項");
        chooser.setApproveButtonToolTipText("確定選擇目前反白的檔案或資料夾");

        // 3. 新增檔案類型過濾器 (範例：只顯示圖片檔案)
        // javax.swing.filechooser.FileNameExtensionFilter imageFilter = new
        // javax.swing.filechooser.FileNameExtensionFilter(
        // "圖片檔案 (*.jpg, *.png, *.gif)", "jpg", "png", "gif");
        // chooser.addChoosableFileFilter(imageFilter);
        // chooser.setFileFilter(imageFilter); // 將此過濾器設為預設 (可選)

        // 4. 允許多選 (如果您的邏輯支援)
        // chooser.setMultiSelectionEnabled(true);

        // 5. 設定是否顯示隱藏檔案
        // chooser.setFileHidingEnabled(false); // 設定為 false 以顯示隱藏檔案

        // --- 設定結束 ---

        int result = chooser.showOpenDialog(parentComponent);
        // System.out.println(result);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFileOrFolder = chooser.getSelectedFile();
            if (selectedFileOrFolder != null) {
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
        for (File f : folder.listFiles()) {
            if (f.isFile()) {
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