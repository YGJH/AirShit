package AirShit;

import AirShit.ui.*;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.atomic.AtomicLong;

public class SendFileGUI extends JFrame {
    // 供 Main.java 等处静态访问
    public static SendFileGUI INSTANCE;
    public static JProgressBar receiveProgressBar;

    // Light Theme Colors
    private static final Color APP_BACKGROUND_LIGHT = new Color(242, 245, 247);
    private static final Color PANEL_BACKGROUND_LIGHT = Color.WHITE;
    private static final Color TEXT_PRIMARY_LIGHT = new Color(45, 55, 72);
    private static final Color TEXT_SECONDARY_LIGHT = new Color(100, 116, 139);
    private static final Color BORDER_COLOR_LIGHT = new Color(226, 232, 240);
    private static final Color LOG_AREA_BACKGROUND_LIGHT = new Color(250, 250, 250); // Slightly off-white for log area

    // Dark Theme Colors
    private static final Color APP_BACKGROUND_DARK = new Color(43, 43, 43);
    private static final Color PANEL_BACKGROUND_DARK = new Color(60, 63, 65);
    private static final Color TEXT_PRIMARY_DARK = new Color(204, 204, 204);
    private static final Color TEXT_SECONDARY_DARK = new Color(153, 153, 153);
    private static final Color BORDER_COLOR_DARK = new Color(81, 81, 81);
    private static final Color LOG_AREA_BACKGROUND_DARK = new Color(45, 48, 51); // Specific dark for log area

    // Accent colors
    private static final Color ACCENT_PRIMARY = new Color(59, 130, 246);
    private static final Color ACCENT_SUCCESS = new Color(16, 185, 129);

    // Current theme colors
    public static Color APP_BACKGROUND;
    public static Color PANEL_BACKGROUND;
    public static Color TEXT_PRIMARY;
    public static Color TEXT_SECONDARY;
    public static Color BORDER_COLOR;
    public static Color LOG_AREA_BACKGROUND; // Current log area background

    // Fonts
    public static final Font FONT_PRIMARY_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    public static final Font FONT_PRIMARY_PLAIN = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    public static final Font FONT_SECONDARY_PLAIN = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    public static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 16);


    private ClientPanel          clientPanel;
    private FileSelectionPanel   filePanel;
    public  SendControlPanel     sendPanel;
    public ReceiveProgressPanel recvPanel;
    private LogPanel             logPanel;
    private JToggleButton        themeToggleButton;
    private boolean              isDarkMode = true;

    public SendFileGUI() {
        super("AirShit File Transfer");
        INSTANCE = this;
        
        setSize(750, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        
        initComponents();
        layoutComponents();
        bindEvents();
        applyTheme(isDarkMode); // Apply initial theme

        log("Welcome to AirShit File Transfer");
        SwingUtilities.invokeLater(() -> setVisible(true));
    }

    private void applyTheme(boolean dark) {
        this.isDarkMode = dark;
        try {
            if (dark) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
                APP_BACKGROUND = APP_BACKGROUND_DARK;
                PANEL_BACKGROUND = PANEL_BACKGROUND_DARK;
                TEXT_PRIMARY = TEXT_PRIMARY_DARK;
                TEXT_SECONDARY = TEXT_SECONDARY_DARK;
                BORDER_COLOR = BORDER_COLOR_DARK;
                LOG_AREA_BACKGROUND = LOG_AREA_BACKGROUND_DARK;
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
                APP_BACKGROUND = APP_BACKGROUND_LIGHT;
                PANEL_BACKGROUND = PANEL_BACKGROUND_LIGHT;
                TEXT_PRIMARY = TEXT_PRIMARY_LIGHT;
                TEXT_SECONDARY = TEXT_SECONDARY_LIGHT;
                BORDER_COLOR = BORDER_COLOR_LIGHT;
                LOG_AREA_BACKGROUND = LOG_AREA_BACKGROUND_LIGHT;
            }
        } catch (Exception ex) {
            System.err.println("Failed to initialize LaF: " + ex.getMessage());
        }

        if (themeToggleButton != null) {
            themeToggleButton.setText(dark ? "Switch to Light Mode" : "Switch to Dark Mode");
            // Also update the toggle button's own background if it's part of a panel that doesn't get APP_BACKGROUND
            // For example, if it's directly on a topBar that should match APP_BACKGROUND:
            if (themeToggleButton.getParent() != null) {
                 themeToggleButton.getParent().setBackground(APP_BACKGROUND);
            }
        }
        
        // Update the look and feel of all components
        SwingUtilities.updateComponentTreeUI(this);

        // Explicitly update the background of the content pane and its direct children if necessary
        if (getContentPane() != null) {
            getContentPane().setBackground(APP_BACKGROUND);
            // If the contentPane has direct children that need APP_BACKGROUND, update them too.
            // In your case, the 'container' JPanel is the contentPane.
            // Its children (topBar and mainContentPanel) also need their backgrounds updated.
            Component[] components = getContentPane().getComponents();
            for (Component component : components) {
                if (component instanceof JPanel) {
                    // This will catch 'topBar' and 'mainContentPanel' if they are direct children
                    // of the 'container' (contentPane)
                    component.setBackground(APP_BACKGROUND);
                }
            }
        }
        
        // Then tell custom panels to update their specific colors
        updateUIsOfChildPanels();
    }

    private void initComponents() {
        themeToggleButton = new JToggleButton("Switch to Dark Mode");
        themeToggleButton.setSelected(isDarkMode);

        clientPanel = new ClientPanel(PANEL_BACKGROUND, TEXT_PRIMARY, TEXT_SECONDARY, ACCENT_PRIMARY, BORDER_COLOR);
        filePanel   = new FileSelectionPanel(PANEL_BACKGROUND, TEXT_PRIMARY, ACCENT_PRIMARY, BORDER_COLOR);
        sendPanel   = new SendControlPanel(APP_BACKGROUND, ACCENT_SUCCESS);
        recvPanel   = new ReceiveProgressPanel(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR);
        // Pass the specific LOG_AREA_BACKGROUND to LogPanel constructor
        logPanel    = new LogPanel(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR, LOG_AREA_BACKGROUND);

        receiveProgressBar = recvPanel.getProgressBar();
        sendPanel.getSendButton().setEnabled(false);
        sendPanel.getSendButton().setFont(FONT_PRIMARY_BOLD);
    }
    
    private void updateUIsOfChildPanels() {
        if (clientPanel != null) clientPanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, TEXT_SECONDARY, ACCENT_PRIMARY, BORDER_COLOR);
        if (filePanel != null) filePanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, ACCENT_PRIMARY, BORDER_COLOR);
        if (sendPanel != null) sendPanel.updateThemeColors(APP_BACKGROUND, ACCENT_SUCCESS);
        if (recvPanel != null) recvPanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR);
        // Pass the specific LOG_AREA_BACKGROUND to LogPanel's update method
        if (logPanel != null) logPanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR, LOG_AREA_BACKGROUND);
    }


    private void layoutComponents() {
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        // topBar.setBackground(APP_BACKGROUND); // This will be set by applyTheme now

        themeToggleButton.setFont(FONT_PRIMARY_PLAIN); // Apply font to toggle button
        topBar.add(themeToggleButton);

        JPanel mainContentPanel = new JPanel(new BorderLayout(15, 15));
        // mainContentPanel.setBackground(APP_BACKGROUND); // This will be set by applyTheme
        mainContentPanel.setBorder(BorderFactory.createEmptyBorder(0, 15, 15, 15));

        mainContentPanel.add(clientPanel, BorderLayout.WEST);

        JPanel right = new JPanel();
        // right.setBackground(APP_BACKGROUND); // This will be set by applyTheme
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.add(filePanel);
        right.add(Box.createVerticalStrut(15));
        right.add(sendPanel);
        right.add(Box.createVerticalStrut(15));
        right.add(recvPanel);
        right.add(Box.createVerticalStrut(15));
        right.add(logPanel);
        mainContentPanel.add(right, BorderLayout.CENTER);

        JPanel container = new JPanel(new BorderLayout());
        // container.setBackground(APP_BACKGROUND); // This will be set by applyTheme
        container.add(topBar, BorderLayout.NORTH);
        container.add(mainContentPanel, BorderLayout.CENTER);
        
        setContentPane(container); // 'container' is now the contentPane
    }

    private void bindEvents() {
        themeToggleButton.addActionListener(e -> {
            applyTheme(themeToggleButton.isSelected());
        });

        clientPanel.getList().addListSelectionListener(e -> updateSendState());
        filePanel.addPropertyChangeListener("selectedFiles", ev -> updateSendState());
        sendPanel.getSendButton().addActionListener(e -> doSend());
    }

    private void updateSendState() {
        boolean ok = clientPanel.getList().getSelectedValue() != null
                  && filePanel.getSelectedFiles() != null;
        sendPanel.getSendButton().setEnabled(ok);
    }

    private void doSend() {
        Client target = clientPanel.getList().getSelectedValue();
        File file;
        try {
            file = filePanel.getSelectedFiles();
        } catch (NoSuchFieldError e) {
            LogPanel.log(e.toString());
            return;
        } 
        if (target == null || file == null) return;

        LogPanel.log("Sending files to " + target.getUserName() + "...");

        TransferCallback callback = new TransferCallback() {
            AtomicLong sentSoFar = new AtomicLong(0);
            long totalBytes;
            int lastPct = -1;

            @Override public void onStart(long totalBytes) {
                this.totalBytes = totalBytes;
                sentSoFar.set(0);
                SwingUtilities.invokeLater(() -> {
                    sendPanel.getSendButton().setEnabled(false);
                    recvPanel.getLabel().setVisible(true);
                    recvPanel.getProgressBar().setVisible(true);
                    recvPanel.getProgressBar().setMaximum(100);
                    recvPanel.getProgressBar().setValue(0);
                });
                LogPanel.log("Total size: " + totalBytes + " bytes");
                Main.sendStatus.set(Main.SEND_STATUS.SEND_WAITING);
            }
            @Override
            public void onComplete(String name) {
                SwingUtilities.invokeLater(() -> {
                    log(name + " is complete");
                    recvPanel.getLabel().setVisible(false);
                    recvPanel.getProgressBar().setVisible(false);
                    sendPanel.getSendButton().setEnabled(true);
                });
                LogPanel.log("File transfer complete.");
                Main.sendStatus.set(Main.SEND_STATUS.SEND_OK);

            }

            @Override public void onProgress(long bytesTransferred) {
                long cumul = sentSoFar.addAndGet(bytesTransferred);
                int pct = (int)(cumul * 100 / totalBytes);
                SwingUtilities.invokeLater(() -> recvPanel.getProgressBar().setValue(pct));
                if (pct % 10 == 0 && pct != lastPct) {
                    lastPct = pct;
                    LogPanel.log("Progress: " + pct + "% (" + formatFileSize(cumul) + ")");
                }
            }

            @Override public void onComplete() {
                SwingUtilities.invokeLater(() -> {
                    recvPanel.getLabel().setVisible(false);
                    recvPanel.getProgressBar().setVisible(false);
                    sendPanel.getSendButton().setEnabled(true);
                });
                LogPanel.log("File transfer complete.");
                Main.sendStatus.set(Main.SEND_STATUS.SEND_OK);
            }
            
            @Override public void onError(Exception e) {
                SwingUtilities.invokeLater(() -> {
                    recvPanel.getLabel().setVisible(false);
                    recvPanel.getProgressBar().setVisible(false);
                    sendPanel.getSendButton().setEnabled(true);
                    
                });
                LogPanel.log("Error: " + e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                LogPanel.log(sw.toString());
                Main.sendStatus.set(Main.SEND_STATUS.SEND_OK);
            }
        };
        new Thread(() -> {
            try {
                FileSender sender = new FileSender(
                    target.getIPAddr(),
                    target.getTCPPort()
                );
                sender.sendFiles(file,
                                 Main.getClient().getUserName(),
                                 callback);
            } catch (Exception ex) {
                callback.onError(ex);
            }
        }, "send-thread").start();
    }

    /** 供 Main.java 调用：写入日志面板 */
    public void log(String msg) {
        if (logPanel != null) {
            LogPanel.log(msg);
        } else {
            System.out.println(msg); 
        }
    }

    public static String formatFileSize(long size) {
        String[] units = {"B","KB","MB","GB"};
        double val = size;
        int idx = 0;
        while (val > 1024 && idx < units.length-1) {
            val /= 1024;
            idx++;
        }
        return String.format("%.2f %s", val, units[idx]);
    }

    public ClientPanel getClientPanel() { // Add this getter
        return clientPanel;
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SendFileGUI::new);
    }
}