package AirShit;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FolderSelector {
    private static String folderName = null;
    public static String getFolderName() {
        return folderName;
    }



    /**
     * 顯示一個資料夾選擇對話框，讓使用者選擇資料夾後，
     * 回傳該資料夾底下所有檔案與子資料夾的 List<File>。
     *
     * @param parentComponent 作為對話框的父元件，若為 null 則無父元件
     * @return 選擇的資料夾底下所有檔案／子資料夾的 List，若使用者取消則回傳空 List
     */
    public static List<File> selectFolderAndListFiles(Component parentComponent) {
        JFileChooser chooser = new JFileChooser();
        // 只允許選擇目錄
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("請選擇一個資料夾");

        int result = chooser.showOpenDialog(parentComponent);
        if (result == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();
            folderName = folder.getAbsolutePath();
            for(int i = folderName.length() - 1; i >= 0; i--) {
                if(folderName.charAt(i) == '\\' 
                        || folderName.charAt(i) == '/') {
                    folderName = folderName.substring(i + 1 , folderName.length());
                    break;
                }
            }
            // folder.listFiles() 可能回傳 null（例：權限不足）
            File[] files = folder.listFiles();
            if (files != null) {
                // 將陣列轉成 List
                return new ArrayList<>(Arrays.asList(files));
            } else {
                // 如果讀取不到任何檔案，就回傳空清單
                return new ArrayList<>();
            }
        } else {
            // 使用者按了「取消」或關閉視窗
            return new ArrayList<>();
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
            for(int i = folderName.length() - 1; i >= 0; i--) {
                if(folderName.charAt(i) == '\\') {
                    folderName = folderName.substring(0, i + 1);
                    break;
                }
            }
            return folderName;
        } else {
            // 使用者按了「取消」或關閉視窗
            return null;
        }
    }

    // public static void main(String[] args) {
    //     // 因為是 Swing GUI，要在 Event Dispatch Thread 中執行
    //     SwingUtilities.invokeLater(() -> 
    //         // 這裡傳入 null 作為 parentComponent，對話框會置中螢幕
    //         List<File> fileList = selectFolderAndListFiles(null);

    //         if (fileList.isEmpty()) {
    //             System.out.println("沒有選擇資料夾，或該資料夾中沒有任何檔案。");
    //         } else {
    //             System.out.println("您選擇的資料夾中包含以下檔案／子資料夾：");
    //             for (File f : fileList) {
    //                 // 印出絕對路徑，也可以用 f.getName() 只印出檔名
    //                 System.out.println(" - " + f.getAbsolutePath());
    //             }
    //         }
    //         // 結束程式
    //         System.exit(0);
    //     });
    // }
}