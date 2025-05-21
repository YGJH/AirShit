package AirShit.ui;

import AirShit.FolderSelector;
import AirShit.SendFileGUI;
import AirShit.NoFileSelectedException;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.io.File;


public class FileSelectionPanel extends JPanel {
    private JLabel lblFiles;
    private JLabel lblIcon;
    private File selected;
    private JButton browseBtn; // Store button to restyle

    // Store current colors
    private Color currentPanelBg;
    private Color currentTextPrimary;
    private Color currentAccentPrimary;
    private Color currentBorderColor;
    private JScrollPane scrollPaneFiles; // Store to update border

    public FileSelectionPanel(Color panelBg, Color textPrimary, Color accentPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;

        setLayout(new BorderLayout(10, 10));
        styleComponents();
    }

    private void styleComponents() {
        setBackground(currentPanelBg);
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(currentBorderColor),
            "File Selection", TitledBorder.LEFT, TitledBorder.TOP,
            SendFileGUI.FONT_TITLE, currentTextPrimary
        ));

        // 初始化圖標
        if (lblIcon == null) {
            lblIcon = new JLabel();
            lblIcon.setPreferredSize(new Dimension(70, 70));
            lblIcon.setHorizontalAlignment(SwingConstants.CENTER);
            lblIcon.setVerticalAlignment(SwingConstants.CENTER);
        }

        // 初始化文字標籤
        if (lblFiles == null) {
            lblFiles = new JLabel("No file selected. Click 'Browse' to choose.");
        }
        lblFiles.setFont(SendFileGUI.FONT_PRIMARY_PLAIN);
        lblFiles.setForeground(currentTextPrimary);
        lblFiles.setVerticalAlignment(SwingConstants.CENTER);
        lblFiles.setHorizontalAlignment(SwingConstants.LEFT);

        // 圖標與文字放在同一個 panel，使用 BorderLayout 讓圖標靠左，文字填滿右邊，並垂直置中
        JPanel fileInfoPanel = new JPanel(new BorderLayout(10, 0)); // 10px 水平間距，0px 垂直間距
        fileInfoPanel.setBackground(currentPanelBg);
        fileInfoPanel.add(lblIcon, BorderLayout.WEST);
        fileInfoPanel.add(lblFiles, BorderLayout.CENTER);

        // 捲軸包裹 fileInfoPanel
        if (scrollPaneFiles == null) {
            scrollPaneFiles = new JScrollPane(fileInfoPanel);
            scrollPaneFiles.setPreferredSize(new Dimension(300, 90));
            
        } else {
            scrollPaneFiles.setViewportView(fileInfoPanel);

        }

        scrollPaneFiles.setBorder(BorderFactory.createLineBorder(currentBorderColor));
        scrollPaneFiles.getViewport().setBackground(currentPanelBg);

        // 初始化瀏覽按鈕
        if (browseBtn == null) {
            browseBtn = new JButton("Browse Files...");
            browseBtn.addActionListener(e -> {
                try {
                    doSelect();
                } catch (NoFileSelectedException ex) {
                    // Handle the exception (e.g., show a message dialog)
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "File Selection", JOptionPane.WARNING_MESSAGE);
                }
            });
        }
        browseBtn.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        browseBtn.setBackground(currentAccentPrimary);
        browseBtn.setForeground(Color.WHITE); // Assuming white text on accent is always good
        browseBtn.setFocusPainted(false);
        browseBtn.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16)); 
        browseBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(currentPanelBg);
        buttonPanel.add(browseBtn);

        removeAll();
        add(scrollPaneFiles, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    private void doSelect() throws NoFileSelectedException {
        File sel = FolderSelector.selectFolderOrFiles(this);
        if (sel == null) {
            // 如果使用者取消選擇，可以選擇不拋出例外，或者保持原樣
            // 如果不拋出例外，則 selected 檔案不會改變
            throw new NoFileSelectedException("No file selected");
        }

        File oldSelectedValue = this.selected; // 1. 儲存舊的 selected 值

        this.selected = sel; // 2. 更新成員變數 selected 為新的選擇

        // 取得系統圖標並嘗試放大
        // Icon fileIcon = FileSystemView.getFileSystemView().getSystemIcon(this.selected); // 使用 this.selected
        Icon fileIcon = new ImageIcon(this.getClass().getResource("/asset/folder.png")); // 使用 this.selected
        if (fileIcon instanceof ImageIcon) {
            Image image = ((ImageIcon) fileIcon).getImage();
            // 確保 lblIcon 已初始化
            if (lblIcon != null) {
                Image scaled = image.getScaledInstance(Math.min(lblIcon.getPreferredSize().width - 10, 30), Math.min(lblIcon.getPreferredSize().height - 10, 30), Image.SCALE_SMOOTH);
                lblIcon.setIcon(new ImageIcon(scaled));
            }
        } else {
            if (lblIcon != null) {
                lblIcon.setIcon(fileIcon);
            }
        }

        if (lblFiles != null) {
            lblFiles.setText(this.selected.getName()); // 使用 this.selected
        }

        // 3. 使用儲存的舊值 (oldSelectedValue) 和新的 selected 值觸發事件
        firePropertyChange("selectedFiles", oldSelectedValue, this.selected);
    }

    public File getSelectedFiles() {
        return selected;
    }

    public void updateThemeColors(Color panelBg, Color textPrimary, Color accentPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;
        styleComponents();
    }
}