package AirShit;

import AirShit.ui.*;
import com.formdev.flatlaf.FlatLightLaf; // Import FlatLaf

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicLong;

public class SendFileGUI extends JFrame {
    // 供 Main.java 等处静态访问
    public static SendFileGUI INSTANCE;
    public static JProgressBar receiveProgressBar;

    // A more "gorgeous" and modern color palette
    private static final Color APP_BACKGROUND = new Color(242, 245, 247); // Light, clean background
    private static final Color PANEL_BACKGROUND = Color.WHITE; // Panels stand out
    private static final Color TEXT_PRIMARY = new Color(45, 55, 72);    // Darker, more readable text
    private static final Color TEXT_SECONDARY = new Color(100, 116, 139); // For less important text
    private static final Color ACCENT_PRIMARY = new Color(59, 130, 246); // A vibrant blue
    private static final Color ACCENT_SUCCESS = new Color(16, 185, 129); // A modern green
    private static final Color BORDER_COLOR = new Color(226, 232, 240); // Subtle borders

    // Modern Fonts (FlatLaf will use system's UI font, which is usually good)
    public static final Font FONT_PRIMARY_BOLD = new Font(Font.SANS_SERIF, Font.BOLD, 14);
    public static final Font FONT_PRIMARY_PLAIN = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    public static final Font FONT_SECONDARY_PLAIN = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    public static final Font FONT_TITLE = new Font(Font.SANS_SERIF, Font.BOLD, 16);


    private ClientPanel          clientPanel;
    private FileSelectionPanel   filePanel;
    private SendControlPanel     sendPanel;
    private ReceiveProgressPanel recvPanel;
    private LogPanel             logPanel;

    public SendFileGUI() {
        super("AirShit File Transfer");
        INSTANCE = this;

        // Apply FlatLaf
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
        } catch (Exception ex) {
            System.err.println("Failed to initialize LaF. Using default.");
        }
        // Re-apply font settings after L&F change if needed, or let FlatLaf handle it.
        // UIManager.put("defaultFont", FONT_PRIMARY_PLAIN);


        setSize(750, 550); // Slightly larger for better spacing
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        initComponents();
        layoutComponents();
        bindEvents();

        log("Welcome to AirShit File Transfer");
        SwingUtilities.invokeLater(() -> setVisible(true));
    }

    private void initComponents() {
        // Pass the new theme colors and fonts to the panels
        clientPanel = new ClientPanel(PANEL_BACKGROUND, TEXT_PRIMARY, TEXT_SECONDARY, ACCENT_PRIMARY, BORDER_COLOR);
        filePanel   = new FileSelectionPanel(PANEL_BACKGROUND, TEXT_PRIMARY, ACCENT_PRIMARY, BORDER_COLOR);
        sendPanel   = new SendControlPanel(APP_BACKGROUND, ACCENT_SUCCESS); // Send button on app background
        recvPanel   = new ReceiveProgressPanel(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR);
        logPanel    = new LogPanel(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR);

        // 把进度条暴露给 Main
        receiveProgressBar = recvPanel.getProgressBar();

        // 初始时禁用发送按钮
        sendPanel.getSendButton().setEnabled(false);
        sendPanel.getSendButton().setFont(FONT_PRIMARY_BOLD);
    }

    private void layoutComponents() {
        JPanel main = new JPanel(new BorderLayout(15, 15)); // Increased gaps
        main.setBackground(APP_BACKGROUND);
        main.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15)); // More padding

        main.add(clientPanel, BorderLayout.WEST);

        JPanel right = new JPanel();
        right.setBackground(APP_BACKGROUND); // Match main background
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));

        // Add components with consistent spacing
        right.add(filePanel);
        right.add(Box.createVerticalStrut(15));
        right.add(sendPanel);
        right.add(Box.createVerticalStrut(15));
        right.add(recvPanel);
        right.add(Box.createVerticalStrut(15));
        right.add(logPanel);

        main.add(right, BorderLayout.CENTER);
        setContentPane(main);
    }

    private void bindEvents() {
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
        Client target       = clientPanel.getList().getSelectedValue();
        String[] files      = filePanel.getSelectedFiles();
        File   fatherDir    = filePanel.getFolder();
        String folderName   = filePanel.getFolderName();
        if (target == null || files == null) return;

        logPanel.log("Sending files to " + target.getUserName() + "...");

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
                logPanel.log("Total size: " + totalBytes + " bytes");
                Main.sendStatus.set(Main.SEND_STATUS.SEND_WAITING);
            }

            @Override public void onProgress(long bytesTransferred) {
                long cumul = sentSoFar.addAndGet(bytesTransferred);
                int pct = (int)(cumul * 100 / totalBytes);
                SwingUtilities.invokeLater(() -> recvPanel.getProgressBar().setValue(pct));
                if (pct % 10 == 0 && pct != lastPct) {
                    lastPct = pct;
                    logPanel.log("Progress: " + pct + "% (" + formatFileSize(cumul) + ")");
                }
            }

            @Override public void onComplete() {
                SwingUtilities.invokeLater(() -> {
                    recvPanel.getProgressBar().setVisible(false);
                    sendPanel.getSendButton().setEnabled(true);
                });
                logPanel.log("File transfer complete.");
                Main.sendStatus.set(Main.SEND_STATUS.SEND_OK);
            }

            @Override public void onError(Exception e) {
                SwingUtilities.invokeLater(() -> {
                    recvPanel.getProgressBar().setVisible(false);
                    sendPanel.getSendButton().setEnabled(true);
                });
                logPanel.log("Error: " + e);
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                logPanel.log(sw.toString());
                Main.sendStatus.set(Main.SEND_STATUS.SEND_OK);
            }
        };

        new Thread(() -> {
            try {
                FileSender sender = new FileSender(
                    target.getIPAddr(),
                    target.getTCPPort(),
                    fatherDir.getAbsolutePath()
                );
                sender.sendFiles(files,
                                 Main.getClient().getUserName(),
                                 folderName,
                                 callback);
            } catch (Exception ex) {
                callback.onError(ex);
            }
        }, "send-thread").start();
    }

    /** 供 Main.java 调用：写入日志面板 */
    public void log(String msg) {
        if (logPanel != null) { // Check if logPanel is initialized
            logPanel.log(msg);
        } else {
            System.out.println("[LOG EARLY] " + msg); // Fallback if logPanel not ready
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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(SendFileGUI::new);
    }
}