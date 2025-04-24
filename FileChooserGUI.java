package AirShit;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;

public class FileChooserGUI extends JFrame {
    
    // 宣告 UI 元件：一個按鈕用來開啟檔案選擇器，一個文字區域用來顯示選擇的檔案資訊
    private JButton openButton;
    private JTextArea textArea;

    // 建構子：初始化 GUI 設定
    public FileChooserGUI() {
        // 設定視窗標題
        super("檔案選擇系統");
        
        // 建立按鈕與設定文字
        openButton = new JButton("開啟檔案選擇器");
        // 建立文字區域，預設大小10行30列，並設為唯獨（使用者無法編輯）
        textArea = new JTextArea(10, 30);
        textArea.setEditable(false);

        // 為按鈕設定動作監聽器
        openButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                // 建立 JFileChooser 物件
                JFileChooser fileChooser = new JFileChooser();
                // 設定初始目錄（這裡設為程式啟動時的當前目錄）
                fileChooser.setCurrentDirectory(new File("."));
                // 允許選擇多個檔案
                fileChooser.setMultiSelectionEnabled(true);
                // 允許選擇檔案和資料夾
                fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                // 顯示開啟檔案的對話框，使用者可以選擇檔案
                int result = fileChooser.showOpenDialog(FileChooserGUI.this);
                
                // 根據使用者選擇的動作進行處理
                if (result == JFileChooser.APPROVE_OPTION) {
                    // 使用者可能選擇了多個檔案
                    File[] selectedFiles = fileChooser.getSelectedFiles();
                    if (selectedFiles.length > 0) {
                        StringBuilder sb = new StringBuilder("選擇的檔案/資料夾:\n");
                        for (File file : selectedFiles) {
                            sb.append(file.getAbsolutePath()).append("\n");
                        }
                        textArea.setText(sb.toString());
                    } else {
                        // 兼容只選擇單一檔案的情況
                        File selectedFile = fileChooser.getSelectedFile();
                        textArea.setText("選擇的檔案/資料夾: " + selectedFile.getAbsolutePath());
                    }
                } else if (result == JFileChooser.CANCEL_OPTION) {
                    // 使用者取消了檔案選取
                    textArea.setText("使用者取消選擇檔案");
                }
            }
        });

        // 設定視窗的主要佈局，並加入元件
        setLayout(new BorderLayout());
        add(openButton, BorderLayout.NORTH);
        add(new JScrollPane(textArea), BorderLayout.CENTER);

        // 設定關閉視窗時結束程式
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // 根據內容調整視窗大小
        pack();
        // 將視窗置中
        setLocationRelativeTo(null);
        // 讓視窗可見
        setVisible(true);
    }

    // 主函數：程式入口點
    public static void ChooseFile(String[] args) {
        // 使用 SwingUtilities.invokeLater 確保在事件分派執行緒上安全地建立及顯示 GUI
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                new FileChooserGUI();
            }
        });
    }

    // 選擇單個檔案
    public static File chooseFile() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File("."));
        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile();
        }
        return null;
    }
    
    // 選擇多個檔案
    public static File[] chooseFiles() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(".")); 
        fileChooser.setMultiSelectionEnabled(true);
        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFiles();
        }
        return new File[0];
    }
    
    // 只選擇資料夾
    public static File chooseDirectory() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File("."));
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFile();
        }
        return null;
    }
    
    // 選擇檔案和/或資料夾
    public static File[] chooseFilesAndDirectories() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File("."));
        fileChooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        fileChooser.setMultiSelectionEnabled(true);
        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            return fileChooser.getSelectedFiles();
        }
        return new File[0];
    }
}