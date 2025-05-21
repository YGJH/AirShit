package AirShit.ui;

import AirShit.FolderSelector;
import AirShit.SendFileGUI;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.io.File;

public class FileSelectionPanel extends JPanel {
    private JLabel lblFiles;
    private JLabel lblIcon;
    private String[] selected;
    private File folder;
    private String folderName;
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
            browseBtn.addActionListener(e -> doSelect());
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

    private void doSelect() {
        File sel = FolderSelector.selectFolderOrFiles(this);
        if (sel == null) {
            return;
        }
        String oldSelected = (selected != null && selected.length > 0) ? selected[0] : null;
        folder = sel.getParentFile();
        folderName = sel.getName();

        // 取得系統圖標並嘗試放大
        Icon fileIcon = FileSystemView.getFileSystemView().getSystemIcon(sel);
        if (fileIcon instanceof ImageIcon) {
            Image image = ((ImageIcon) fileIcon).getImage();
            Image scaled = image.getScaledInstance(30, 30, Image.SCALE_SMOOTH);
            lblIcon.setIcon(new ImageIcon(scaled));
        } else {
            lblIcon.setIcon(fileIcon);
        }

        if (sel.isDirectory()) {
            selected = FolderSelector.listFilesRecursivelyWithRelativePaths(sel);
            StringBuilder sb = new StringBuilder("<html><b>Folder:</b> " + sel.getName() + "<br>");
            int count = 0;
            for (String f : selected) {
                if (count < 5) {
                    sb.append("&nbsp;&nbsp;- ").append(f).append("<br>");
                }
                count++;
            }
            if (count > 5) {
                sb.append("&nbsp;&nbsp;...and ").append(count - 5).append(" more files.");
            }
            sb.append("</html>");
            lblFiles.setText(sb.toString());
        } else {
            selected = new String[]{sel.getName()};
            lblFiles.setText("<html><b>File:</b> " + sel.getName() + "<br><b>Path:</b> " + sel.getParent() + "</html>");
        }
        firePropertyChange("selectedFiles", oldSelected, selected);
    }

    public String[] getSelectedFiles() {
        return selected;
    }

    public File getFolder() {
        return folder;
    }

    public String getFolderName() {
        return folderName;
    }

    public void updateThemeColors(Color panelBg, Color textPrimary, Color accentPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;
        styleComponents();
    }
}