package AirShit; // 定義包名

import javax.swing.*; // 引入 Swing 圖形介面庫
import java.awt.*; // 引入 AWT 佈局及其他相關類
import java.awt.event.*; // 引入 AWT 事件處理類
import java.io.File; // 引入檔案處理類
import java.util.Hashtable; // 引入 Hashtable 集合類

public class SendFileGUI extends JFrame { // 定義 SendFileGUI 類，繼承自 JFrame
    private JButton sendFileButton; // 定義發送檔案按鈕變數
    private JList<String> userList; // 定義顯示用戶列表的 JList 變數
    private DefaultListModel<String> listModel; // 定義列表模型，儲存用戶資料
    
    public SendFileGUI() { // SendFileGUI 建構子
        super("Send File GUI"); // 呼叫父類別構造器，設定視窗標題
        setLayout(new BorderLayout()); // 設定視窗主佈局為 BorderLayout

        listModel = new DefaultListModel<>(); // 建立新的列表模型
        userList = new JList<>(listModel); // 建立 JList 並設置其模型
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); // 設定只能單選
        JScrollPane listScrollPane = new JScrollPane(userList); // 使用捲軸包裝 JList
        
        sendFileButton = new JButton("Send File"); // 建立發送檔案按鈕
        
        add(listScrollPane, BorderLayout.CENTER); // 將捲軸面板放置於版面中央
        add(sendFileButton, BorderLayout.SOUTH); // 將按鈕放置於版面下方
        
        sendFileButton.addActionListener(new ActionListener() { // 為發送按鈕添加事件監聽器
            @Override
            public void actionPerformed(ActionEvent e) { // 定義按鈕點擊時執行的方法
                String selectedUser = userList.getSelectedValue(); // 取得被選中的用戶名稱
                if (selectedUser == null) { // 如果沒有選擇用戶
                    JOptionPane.showMessageDialog(SendFileGUI.this, "Please select a receiver from the list!",
                            "No Receiver Selected", JOptionPane.ERROR_MESSAGE); // 顯示錯誤對話框
                    return; // 結束方法，返回不再執行後續程式碼
                }
                
                File file = FileChooserGUI.chooseFile(); // 呼叫檔案選擇器來選擇檔案
                if (file == null) { // 如果未選取到檔案
                    JOptionPane.showMessageDialog(SendFileGUI.this, "No file was chosen!", 
                            "File Not Selected", JOptionPane.INFORMATION_MESSAGE); // 顯示資訊對話框
                    return; // 結束方法，返回不再執行後續程式碼
                }
                
                boolean success = Main.sendFileToUser(selectedUser, file); // 呼叫 Main 傳送檔案給指定用戶
                if (success) { // 如果檔案傳送成功
                    JOptionPane.showMessageDialog(SendFileGUI.this, "File sent successfully to " + selectedUser,
                            "Success", JOptionPane.INFORMATION_MESSAGE); // 顯示成功對話框
                } else { // 如果檔案傳送失敗
                    JOptionPane.showMessageDialog(SendFileGUI.this, "File sending failed to " + selectedUser,
                            "Failure", JOptionPane.ERROR_MESSAGE); // 顯示錯誤對話框
                }
            }
        });
        
        Timer timer = new Timer(3000, new ActionListener() { // 建立定時器，每3000毫秒執行一次任務
            public void actionPerformed(ActionEvent e) { // 定義定時器觸發時執行的方法
                refreshUserList(); // 刷新用戶列表
            }
        });
        timer.start(); // 啟動定時器
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // 設定視窗關閉時結束程式
        pack(); // 自動調整視窗至適合大小
        setLocationRelativeTo(null); // 將視窗定位在螢幕中央
        setVisible(true); // 將視窗設定為可見
    }
    
    private void refreshUserList() { // 定義刷新用戶列表方法
        listModel.clear(); // 清空現有的列表數據
        Hashtable<String, Client> clients = Main.getClientPorts(); // 從 Main 取得所有用戶及其連線資訊
        if(clients != null) { // 如果用戶列表不為空
            for(String username : clients.keySet()){ // 迭代用戶列表中的每個用戶名稱
                listModel.addElement(username); // 將用戶名稱加入列表模型
            }
        }
    }
    
    public static void main(String[] args) { // 主方法，程式進入點
        SwingUtilities.invokeLater(() -> new SendFileGUI()); // 使用事件佇列創建並顯示 GUI
    }
}
