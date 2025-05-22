package AirShit.ui;

import AirShit.FolderSelector;
import AirShit.SendFileGUI;
import AirShit.NoFileSelectedException;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.List;

public class FileSelectionPanel extends JPanel {
    private JLabel lblFiles;
    private JLabel lblIcon;
    private String[] selected;
    private File folder;
    private String folderName;
    private JButton browseBtn;

    private Color currentPanelBg;
    private Color currentTextPrimary;
    private Color currentAccentPrimary;
    private Color currentBorderColor;
    private JScrollPane scrollPaneFiles;

    public FileSelectionPanel(Color panelBg, Color textPrimary, Color accentPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;

        setLayout(new BorderLayout(10, 10));
        styleComponents();

        ToolTipManager.sharedInstance().setInitialDelay(200);

        if (lblIcon != null && lblIcon.getMouseListeners().length == 0) {
            lblIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (selected != null && selected.length > 0) {
                        File targetFile = new File(folder, selected[0]);
                        if (targetFile.exists()) {
                            try {
                                Desktop.getDesktop().open(targetFile);
                            } catch (Exception ex) {
                                JOptionPane.showMessageDialog(FileSelectionPanel.this,
                                        "Cannot preview file: " + ex.getMessage(),
                                        "Preview Error", JOptionPane.ERROR_MESSAGE);
                            }
                        }
                    }
                }
            });
        }

        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) {
                        handleSelectedFile(files.get(0));
                        return true;
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(FileSelectionPanel.this,
                            "Error during file drop: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        });
    }

    private void styleComponents() {
        setBackground(currentPanelBg);
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(currentBorderColor),
            "File Selection", TitledBorder.LEFT, TitledBorder.TOP,
            SendFileGUI.FONT_TITLE, currentTextPrimary
        ));

        if (lblIcon == null) {
            lblIcon = new JLabel();
            lblIcon.setPreferredSize(new Dimension(70, 70));
            lblIcon.setHorizontalAlignment(SwingConstants.CENTER);
            lblIcon.setVerticalAlignment(SwingConstants.CENTER);
        }

        if (lblFiles == null) {
            lblFiles = new JLabel("No file selected. Click 'Browse' to choose.");
        }
        lblFiles.setFont(SendFileGUI.FONT_PRIMARY_PLAIN);
        lblFiles.setForeground(currentTextPrimary);
        lblFiles.setVerticalAlignment(SwingConstants.CENTER);
        lblFiles.setHorizontalAlignment(SwingConstants.LEFT);

        JPanel fileInfoPanel = new JPanel(new BorderLayout(10, 0));
        fileInfoPanel.setBackground(currentPanelBg);
        fileInfoPanel.add(lblIcon, BorderLayout.WEST);
        fileInfoPanel.add(lblFiles, BorderLayout.CENTER);

        if (scrollPaneFiles == null) {
            scrollPaneFiles = new JScrollPane(fileInfoPanel);
            scrollPaneFiles.setPreferredSize(new Dimension(300, 90));
        } else {
            scrollPaneFiles.setViewportView(fileInfoPanel);
        }

        scrollPaneFiles.setBorder(BorderFactory.createLineBorder(currentBorderColor));
        scrollPaneFiles.getViewport().setBackground(currentPanelBg);

        if (browseBtn == null) {
            browseBtn = new JButton("Browse Files...");
            browseBtn.addActionListener(e -> {
                File sel = FolderSelector.selectFolderOrFiles(this);
                if (sel == null) {
                    JOptionPane.showMessageDialog(this, "No file selected", "File Selection", JOptionPane.WARNING_MESSAGE);
                } else {
                    handleSelectedFile(sel);
                }
            });
        }
        browseBtn.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        browseBtn.setBackground(currentAccentPrimary);
        browseBtn.setForeground(Color.WHITE);
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

    private void handleSelectedFile(File sel) {
        if (sel == null) return;

        String oldSelected = (selected != null && selected.length > 0) ? selected[0] : null;
        folder = sel.getParentFile();
        folderName = sel.getName();

        Icon fileIcon = FileSystemView.getFileSystemView().getSystemIcon(sel);
        if (fileIcon instanceof ImageIcon) {
            Image image = ((ImageIcon) fileIcon).getImage();
            Image scaled = image.getScaledInstance(30, 30, Image.SCALE_SMOOTH);
            lblIcon.setIcon(new ImageIcon(scaled));
        } else {
            lblIcon.setIcon(fileIcon);
        }

        lblIcon.setToolTipText("click to preview");
        lblIcon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

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
