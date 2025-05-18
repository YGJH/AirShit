package AirShit.ui;

import AirShit.FolderSelector;
import AirShit.SendFileGUI; // For Font constants

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.File;

public class FileSelectionPanel extends JPanel {
    private JLabel lblFiles;
    private String[] selected;
    private File folder;
    private String folderName;

    // Updated constructor to accept borderColor
    public FileSelectionPanel(Color panelBg, Color textPrimary, Color accentPrimary, Color borderColor) {
        setLayout(new BorderLayout(10, 10));
        setBackground(panelBg);
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(borderColor), // Use borderColor
            "File Selection", TitledBorder.LEFT, TitledBorder.TOP,
            SendFileGUI.FONT_TITLE, textPrimary // Use new font and text color
        ));

        lblFiles = new JLabel("No file selected. Click 'Browse' to choose.");
        lblFiles.setFont(SendFileGUI.FONT_PRIMARY_PLAIN);
        lblFiles.setForeground(textPrimary);
        lblFiles.setHorizontalAlignment(SwingConstants.CENTER);
        JScrollPane sp = new JScrollPane(lblFiles);
        sp.setBorder(BorderFactory.createLineBorder(borderColor)); // Use borderColor
        sp.setPreferredSize(new Dimension(250, 80)); // Adjusted height

        JButton browseBtn = new JButton("Browse Files...");
        browseBtn.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        browseBtn.setBackground(accentPrimary);
        browseBtn.setForeground(Color.WHITE);
        browseBtn.setFocusPainted(false);
        browseBtn.addActionListener(e -> doSelect());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(panelBg);
        buttonPanel.add(browseBtn);

        add(sp, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH); // Place button at the bottom
    }

    private void doSelect() {
        File sel = FolderSelector.selectFolderOrFiles(this);
        if (sel == null) {
            // Optionally, reset if selection is cancelled
            // selected = null;
            // folder = null;
            // folderName = null;
            // lblFiles.setText("No file selected. Click 'Browse' to choose.");
            // firePropertyChange("selectedFiles", "something", null); // Notify change
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
                if (count < 5) { // Show first 5 files
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
        // Notify SendFileGUI that files have been selected/changed
        firePropertyChange("selectedFiles", oldSelected, selected);
    }

    public String[] getSelectedFiles() { return selected; }
    public File     getFolder()        { return folder;   }
    public String   getFolderName()    { return folderName;}
}