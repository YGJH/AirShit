package AirShit.ui;

import AirShit.FolderSelector;
import AirShit.SendFileGUI;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;

public class FileSelectionPanel extends JPanel {
    private JLabel lblFiles;
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

        if (lblFiles == null) {
            lblFiles = new JLabel("No file selected. Click 'Browse' to choose.");
            lblFiles.setHorizontalAlignment(SwingConstants.CENTER);
        }
        lblFiles.setFont(SendFileGUI.FONT_PRIMARY_PLAIN);
        lblFiles.setForeground(currentTextPrimary);

        if (scrollPaneFiles == null) {
            scrollPaneFiles = new JScrollPane(lblFiles);
            scrollPaneFiles.setPreferredSize(new Dimension(250, 80));
        }
        scrollPaneFiles.setBorder(BorderFactory.createLineBorder(currentBorderColor));
        scrollPaneFiles.getViewport().setBackground(currentPanelBg);


        if (browseBtn == null) {
            browseBtn = new JButton("Browse Files...");
            browseBtn.addActionListener(e -> doSelect());
        }
        browseBtn.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        browseBtn.setBackground(currentAccentPrimary);
        browseBtn.setForeground(Color.WHITE); // Assuming white text on accent is always good
        browseBtn.setFocusPainted(false);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(currentPanelBg);
        buttonPanel.add(browseBtn);

        removeAll();
        add(scrollPaneFiles, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    public void updateThemeColors(Color panelBg, Color textPrimary, Color accentPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;
        styleComponents();
    }

    private void doSelect() {
        File sel = FolderSelector.selectFolderOrFiles(this);
        if (sel == null) {
            return;
        }
        String oldSelected = (selected != null && selected.length > 0) ? selected[0] : null;
        folder = sel.getParentFile();
        folderName = sel.getName();
        if (sel.isDirectory()) {
            selected = FolderSelector.listFilesRecursivelyWithRelativePaths(sel);
            StringBuilder sb = new StringBuilder("<html><b>Folder:</b> " + sel.getName() + "<br>");
            int count = 0;
            for (String f : selected) {
                if (count < 5) { sb.append("&nbsp;&nbsp;- ").append(f).append("<br>"); }
                count++;
            }
            if (count > 5) { sb.append("&nbsp;&nbsp;...and ").append(count - 5).append(" more files."); }
            sb.append("</html>");
            lblFiles.setText(sb.toString());
        } else {
            selected = new String[]{sel.getName()};
            lblFiles.setText("<html><b>File:</b> " + sel.getName() + "<br><b>Path:</b> " + sel.getParent() + "</html>");
        }
        firePropertyChange("selectedFiles", oldSelected, selected);
    }

    public String[] getSelectedFiles() { return selected; }
    public File     getFolder()        { return folder;   }
    public String   getFolderName()    { return folderName;}
}