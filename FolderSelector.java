package AirShit;
import javax.swing.*;
import java.awt.*;
import java.io.File;


public class FolderSelector {
    private static String folderName = null;
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
        // 只允許選擇目錄
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setDialogTitle("請選擇一個資料夾");

        int result = chooser.showOpenDialog(parentComponent);
        System.out.println(result);
        if (result == JFileChooser.APPROVE_OPTION) {
            File folder = chooser.getSelectedFile();
            folderName = folder.getAbsolutePath();
            File file = new File(folderName);
            if(file.isFile()) {
                File[] files = new File[1];
                files[0] = file;
                return files;
            }
            // 取得資料夾名稱，並去掉路徑部分
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
                return files;
            } else {
                // 如果讀取不到任何檔案，就回傳空清單
                return null;
            }
        } else {
            // 使用者按了「取消」或關閉視窗
            return null;
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