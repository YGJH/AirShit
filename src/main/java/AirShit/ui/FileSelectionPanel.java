package AirShit.ui;

import AirShit.FolderSelector;
import AirShit.SendFileGUI;
import AirShit.NoFileSelectedException;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.awt.dnd.DropTargetListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.util.List;

public class FileSelectionPanel extends JPanel implements DropTargetListener {
    private JLabel lblFiles;
    private JLabel lblIcon;
    private File selected;
    private File folder;
    private String folderName;
    private JButton browseBtn;
    private JButton clearBtn; // Added clear button
    private JPanel fileInfoPanel; // Made into a class member
    private Border originalBorder; // To store the original border for drag feedback

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

        // Initialize fileInfoPanel as it's now a member
        fileInfoPanel = new JPanel(new BorderLayout(10, 0));

        setLayout(new BorderLayout(10, 10));
        styleComponents(); // originalBorder will be set here

        ToolTipManager.sharedInstance().setInitialDelay(200);

        if (lblIcon != null && lblIcon.getMouseListeners().length == 0) {
            lblIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (selected != null) {
                        if (selected.exists()) {
                            try {
                                Desktop.getDesktop().open(selected);
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

        // Setup DropTarget for visual feedback during drag
        // Implemented DropTargetListener interface for this class
        new DropTarget(this, DnDConstants.ACTION_COPY_OR_MOVE, this, true, null);

        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support))
                    return false;
                try {
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) {
                        handleSelectedFile(files.get(0)); // Use existing handler
                        return true;
                    }
                } catch (UnsupportedFlavorException | IOException ex) { // Adjusted catch
                    JOptionPane.showMessageDialog(FileSelectionPanel.this,
                            "Error during file drop: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
                return false;
            }
        });
    }

    private void styleComponents() {
        setBackground(currentPanelBg);
        Border titledBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(currentBorderColor),
                "File Selection", TitledBorder.LEFT, TitledBorder.TOP,
                SendFileGUI.FONT_TITLE, currentTextPrimary);
        setBorder(titledBorder);
        if (this.originalBorder == null) { // Set original border only once or when theme changes it
            this.originalBorder = titledBorder;
        }

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

        // fileInfoPanel is now a member, initialized in constructor
        fileInfoPanel.setBackground(selected != null ? currentAccentPrimary.darker() : currentPanelBg);
        fileInfoPanel.removeAll(); // Clear previous components if any, for re-styling
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
            try {
                java.net.URL folderIconURL = getClass().getResource("/asset/folder.png");
                if (folderIconURL != null) {
                    ImageIcon folderIcon = new ImageIcon(folderIconURL);
                    Image scaledImg = folderIcon.getImage().getScaledInstance(20, 20, Image.SCALE_SMOOTH);
                    browseBtn.setIcon(new ImageIcon(scaledImg));
                } else {
                    System.err.println("Browse button icon '/asset/folder.png' not found.");
                }
            } catch (Exception e) {
                System.err.println("Error loading browse button icon: " + e.getMessage());
            }
            browseBtn.addActionListener(e -> {
                File sel = FolderSelector.selectFolderOrFiles(this);
                // No need to show JOptionPane for null selection if it's a cancel action
                if (sel != null) {
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

        if (clearBtn == null) {
            clearBtn = new JButton("Clear");
            clearBtn.addActionListener(e -> clearSelection());
        }
        clearBtn.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        clearBtn.setBackground(currentAccentPrimary); // Or a different color like a muted red/gray
        clearBtn.setForeground(Color.WHITE);
        clearBtn.setFocusPainted(false);
        clearBtn.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearBtn.setEnabled(selected != null);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.setBackground(currentPanelBg);
        buttonPanel.add(clearBtn);
        buttonPanel.add(Box.createHorizontalStrut(5));
        buttonPanel.add(browseBtn);

        removeAll();
        add(scrollPaneFiles, BorderLayout.CENTER);
        add(buttonPanel, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    private void handleSelectedFile(File sel) {
        if (sel == null)
            return;

        File oldSelected = this.selected; // 保存舊的選擇
        this.selected = sel;
        this.folder = sel.getParentFile();
        this.folderName = sel.getName();

        Icon fileIcon = FileSystemView.getFileSystemView().getSystemIcon(sel);
        if (fileIcon instanceof ImageIcon) {
            Image image = ((ImageIcon) fileIcon).getImage();
            // 動態縮放 icon，確保符合 lblIcon 尺寸
            int size = Math.min(lblIcon.getPreferredSize().width - 10, 30); // Keep icon size reasonable
            Image scaled = image.getScaledInstance(size, size, Image.SCALE_SMOOTH);
            lblIcon.setIcon(new ImageIcon(scaled));
        } else {
            lblIcon.setIcon(fileIcon);
        }

        lblIcon.setToolTipText("Click to open/preview"); // Changed tooltip
        lblIcon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        if (sel.isDirectory()) {
            // 顯示資料夾內的部分檔案列表
            String[] filesInFolder = FolderSelector.listFilesRecursivelyWithRelativePaths(sel);
            StringBuilder sb = new StringBuilder("<html><b>Folder:</b> " + sel.getName() + "<br>");
            int count = 0;
            for (String f : filesInFolder) {
                if (count < 5) { // Show up to 5 files
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
            lblFiles.setText("<html><b>File:</b> " + sel.getName() + "<br><b>Path:</b> " + sel.getParent() + "</html>");
        }

        fileInfoPanel.setBackground(currentAccentPrimary.darker()); // Highlight background
        if (clearBtn != null)
            clearBtn.setEnabled(true);
        firePropertyChange("selectedFile", oldSelected, selected);
        revalidate();
        repaint();
    }

    private void clearSelection() {
        File oldSelected = this.selected;
        this.selected = null;
        this.folder = null;
        this.folderName = null;

        lblFiles.setText("No file selected. Click 'Browse' to choose.");
        lblIcon.setIcon(null);
        lblIcon.setToolTipText(null);
        lblIcon.setCursor(Cursor.getDefaultCursor());

        fileInfoPanel.setBackground(currentPanelBg); // Reset background
        if (clearBtn != null)
            clearBtn.setEnabled(false);

        firePropertyChange("selectedFile", oldSelected, null);
        revalidate();
        repaint();
    }

    public File getSelectedFiles() {
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

        // Update the original border to reflect new theme, before styleComponents might
        // use it
        this.originalBorder = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(this.currentBorderColor),
                "File Selection", TitledBorder.LEFT, TitledBorder.TOP,
                SendFileGUI.FONT_TITLE, this.currentTextPrimary);
        // Ensure the panel's current border is also updated to the new originalBorder
        setBorder(this.originalBorder);


        styleComponents(); // This will re-style all components

        // Explicitly set fileInfoPanel background based on current selection state and
        // new theme
        if (selected != null) {
            fileInfoPanel.setBackground(this.currentAccentPrimary.darker());
        } else {
            fileInfoPanel.setBackground(this.currentPanelBg);
        }
        // Ensure clear button colors are updated
        if (clearBtn != null) {
            clearBtn.setBackground(this.currentAccentPrimary);
            clearBtn.setForeground(Color.WHITE);
        }
    }

    // DropTargetListener methods for drag-and-drop visual feedback
    @Override
    public void dragEnter(DropTargetDragEvent dtde) {
        if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            dtde.acceptDrag(DnDConstants.ACTION_COPY);
            if (originalBorder instanceof TitledBorder) {
                TitledBorder tb = (TitledBorder) originalBorder;
                Border dragOverBorder = BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(currentAccentPrimary, 2), // Thicker, accented line
                        tb.getTitle(), tb.getTitleJustification(), tb.getTitlePosition(),
                        tb.getTitleFont(), currentAccentPrimary); // Title color also accent
                setBorder(dragOverBorder);
            } else {
                // Fallback for a non-TitledBorder (should not happen with current setup)
                setBorder(BorderFactory.createLineBorder(currentAccentPrimary, 2));
            }
            repaint();
        } else {
            dtde.rejectDrag();
        }
    }

    @Override
    public void dragOver(DropTargetDragEvent dtde) {
        // Not strictly necessary if dragEnter provides sufficient feedback
    }

    @Override
    public void dropActionChanged(DropTargetDragEvent dtde) {
        // Not strictly necessary for simple copy actions
    }

    @Override
    public void dragExit(DropTargetEvent dte) {
        setBorder(originalBorder); // Revert to the original border
        repaint();
    }

    @Override
    public void drop(DropTargetDropEvent dtde) {
        // Reset border after drop attempt (TransferHandler will handle the data)
        setBorder(originalBorder);
        repaint();
        // Note: The actual data import is handled by the TransferHandler set on this panel.
        // The TransferHandler should call dtde.acceptDrop() or dtde.rejectDrop()
        // and dtde.dropComplete().
    }
}
