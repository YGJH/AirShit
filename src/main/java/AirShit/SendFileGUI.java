package AirShit;

import AirShit.ui.*;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.List;
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

    private ClientPanel clientPanel;
    private FileSelectionPanel filePanel;
    public SendControlPanel sendPanel;
    public ReceiveProgressPanel recvPanel;
    private LogPanel logPanel;
    private JToggleButton themeToggleButton;
    private JButton refreshButton;
    private JButton addClientButton;
    private boolean isDarkMode = true;
    private JTextField portField;
    private JTextField groupField;
    private JComboBox<NetworkInterfaceItem> networkInterfaceCombo; // 新增網卡選擇框

    public SendFileGUI() {
        super("AirShit File Transfer");
        INSTANCE = this;

        // Set application icon
        try {
            java.net.URL iconURL = getClass().getResource("/asset/kitty.ico");
            if (iconURL != null) {
                setIconImage(new ImageIcon(iconURL).getImage());
            } else {
                System.err.println(
                        "Application icon '/asset/kitty.ico' not found. Ensure it's in src/main/resources/asset/");
            }
        } catch (Exception e) {
            System.err.println("Error loading application icon: " + e.getMessage());
        }

        setSize(1350, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        applyTheme(isDarkMode); // Apply initial theme
        initComponents();

        layoutComponents();
        bindEvents();
        
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
            // Also update the toggle button's own background if it's part of a panel that
            // doesn't get APP_BACKGROUND
            // For example, if it's directly on a topBar that should match APP_BACKGROUND:
            if (themeToggleButton.getParent() != null) {
                themeToggleButton.getParent().setBackground(APP_BACKGROUND);
            }
        }

        // Update the look and feel of all components
        SwingUtilities.updateComponentTreeUI(this);

        // Explicitly update the background of the content pane and its direct children
        // if necessary
        if (getContentPane() != null) {
            getContentPane().setBackground(APP_BACKGROUND);
            // If the contentPane has direct children that need APP_BACKGROUND, update them
            // too.
            // In your case, the 'container' JPanel is the contentPane.
            // Its children (topBar and mainContentPanel) also need their backgrounds
            // updated.
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

        // 新增 Refresh 按鈕
        refreshButton = new JButton("Refresh");
        refreshButton.setFont(FONT_PRIMARY_PLAIN);        // 初始化網卡選擇框
        networkInterfaceCombo = new JComboBox<>();
        networkInterfaceCombo.setFont(FONT_PRIMARY_PLAIN);
        networkInterfaceCombo.setMaximumRowCount(10); // 啟用滾動功能，最多顯示10個項目
        refreshNetworkInterfaceList();

        // 新增 Add Client 按鈕
        addClientButton = new JButton("Add Client");
        addClientButton.setFont(FONT_PRIMARY_PLAIN);

        clientPanel = new ClientPanel(PANEL_BACKGROUND, TEXT_PRIMARY, TEXT_SECONDARY, ACCENT_PRIMARY, BORDER_COLOR);
        filePanel = new FileSelectionPanel(PANEL_BACKGROUND, TEXT_PRIMARY, ACCENT_PRIMARY, BORDER_COLOR);
        sendPanel = new SendControlPanel(APP_BACKGROUND, ACCENT_SUCCESS);
        recvPanel = new ReceiveProgressPanel(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR);
        logPanel = new LogPanel(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR, LOG_AREA_BACKGROUND);

        // DISCOVERY_PORT 输入框
        portField = new JTextField(String.valueOf(Main.DISCOVERY_PORT), 6);
        portField.setFont(FONT_PRIMARY_PLAIN);
        // Multicast IP 输入框
        groupField = new JTextField(Main.MULTICAST_GROUP, 15);
        groupField.setFont(FONT_PRIMARY_PLAIN);

        receiveProgressBar = recvPanel.getProgressBar();
        sendPanel.getSendButton().setEnabled(false);
        sendPanel.getSendButton().setFont(FONT_PRIMARY_BOLD);
    }    // 添加刷新網卡列表的方法
    private void refreshNetworkInterfaceList() {
        networkInterfaceCombo.removeAllItems();
        
        // 取得可用的網卡
        List<NetworkInterface> nets;
        try {
            nets = Main.getAvailableNetworkInterfaces();
            // ...後續邏輯...
        } catch (SocketException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error retrieving network interfaces: " + e.getMessage(),
                    "Network Error", JOptionPane.ERROR_MESSAGE);
            return;
            // 顯示錯誤給使用者
        }
        
        // 找出當前正在使用的網卡
        NetworkInterface currentInterface = null;
        if (Main.isUsingAutoDetection()) {
            // 使用當前 IP 找出對應的網卡
            String currentIP = Main.getClient().getIPAddr();
            for (NetworkInterface ni : nets) {
                String niIP = Main.getIPFromNetworkInterface(ni);
                if (niIP != null && niIP.equals(currentIP)) {
                    currentInterface = ni;
                    break;
                }
            }
        } else {
            currentInterface = Main.getSelectedNetworkInterface();
        }
        
        // 添加所有可用的網卡到選擇框（不包含 auto-detection 選項）
        for (NetworkInterface ni : nets) {
            System.out.println(ni.getName());
            networkInterfaceCombo.addItem(new NetworkInterfaceItem(ni));
        }
        
        // 設置當前選中的網卡
        if (currentInterface != null) {
            for (int i = 0; i < networkInterfaceCombo.getItemCount(); i++) {
                NetworkInterfaceItem item = networkInterfaceCombo.getItemAt(i);
                if (item.getNetworkInterface().equals(currentInterface)) {
                    networkInterfaceCombo.setSelectedIndex(i);
                    break;
                }
            }
        } else if (networkInterfaceCombo.getItemCount() > 0) {
            // 如果沒有當前選中的網卡，選擇第一個可用的
            networkInterfaceCombo.setSelectedIndex(0);
        }
        
        // 設置 tooltip
        networkInterfaceCombo.setToolTipText("<html>" +
            "<b>Network Interface Selection</b><br>" +
            "🌐 <b>Manual Selection:</b> Choose a specific network interface<br>" +
            "<i>Changing interface will clear client list and restart discovery</i>" +
            "</html>");
    }

    private void updateUIsOfChildPanels() {
        if (clientPanel != null)
            clientPanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, TEXT_SECONDARY, ACCENT_PRIMARY, BORDER_COLOR);
        if (filePanel != null)
            filePanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, ACCENT_PRIMARY, BORDER_COLOR);
        if (sendPanel != null)
            sendPanel.updateThemeColors(APP_BACKGROUND, ACCENT_SUCCESS);
        if (recvPanel != null)
            recvPanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR);
        // Pass the specific LOG_AREA_BACKGROUND to LogPanel's update method
        if (logPanel != null)
            logPanel.updateThemeColors(PANEL_BACKGROUND, TEXT_PRIMARY, BORDER_COLOR, LOG_AREA_BACKGROUND);
    }

    private void layoutComponents() {
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        themeToggleButton.setFont(FONT_PRIMARY_PLAIN);
        topBar.add(themeToggleButton);
        topBar.add(refreshButton);
        
        // 添加網卡選擇框
        topBar.add(new JLabel("Network Interface:"));
        topBar.add(networkInterfaceCombo);
        
        topBar.add(new JLabel("DISCOVERY_PORT:"));
        topBar.add(portField);
        topBar.add(new JLabel("Multicast IP:"));
        topBar.add(groupField);

        JPanel mainContentPanel = new JPanel(new BorderLayout(15, 15));
        mainContentPanel.setBorder(BorderFactory.createEmptyBorder(0, 15, 15, 15));
        // wrap client panel and addClient button
        JPanel leftPanel = new JPanel(new BorderLayout(5,5));
        leftPanel.add(clientPanel, BorderLayout.CENTER);
        leftPanel.add(addClientButton, BorderLayout.SOUTH);
        mainContentPanel.add(leftPanel, BorderLayout.WEST);

        JPanel right = new JPanel();
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
        container.add(topBar, BorderLayout.NORTH);
        container.add(mainContentPanel, BorderLayout.CENTER);

        setContentPane(container);
    }

    private void bindEvents() {
        themeToggleButton.addActionListener(e -> {
            applyTheme(themeToggleButton.isSelected());
        });
        // add client manually
        addClientButton.addActionListener(e -> {
            String ip = JOptionPane.showInputDialog(this, "Enter client IP:");
            if (ip == null || ip.isBlank()) return;
            String portStr = JOptionPane.showInputDialog(this, "Enter client port:");
            try {
                int port = Integer.parseInt(portStr.trim());
                Main.manuallyAddClient(ip.trim(), port);
                clientPanel.refreshGuiListOnly();
                LogPanel.log("Manually added client: " + ip + ":" + port);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Invalid port: " + portStr);
            }
        });        // Refresh 按一下就重啟整個程式
        refreshButton.addActionListener(e -> Main.restart());        // 網卡選擇框事件處理
        networkInterfaceCombo.addActionListener(e -> {
            NetworkInterfaceItem selectedItem = (NetworkInterfaceItem) networkInterfaceCombo.getSelectedItem();
            if (selectedItem != null) {
                NetworkInterface selectedNI = selectedItem.getNetworkInterface();
                if (selectedNI != null) {
                    log("Network interface manually set to: " + selectedNI.getDisplayName());
                    Main.setSelectedNetworkInterface(selectedNI);
                }
            }
        });

        clientPanel.getList().addListSelectionListener(e -> updateSendState());
        filePanel.addPropertyChangeListener("selectedFile", ev -> updateSendState()); // 監聽檔案選擇變更
        sendPanel.getSendButton().addActionListener(e -> doSend());

        // 端口输入校验 & 更新
        portField.addActionListener(e -> {
            try {
                int p = Integer.parseInt(portField.getText().trim());
                // 接受范围 1–65536
                if (p < 1 || p > 65535) throw new NumberFormatException();
                Main.setDiscoveryPort(p);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                        "請輸入 1–65535 之間的端口號",
                        "端口錯誤", JOptionPane.ERROR_MESSAGE);
                portField.setText(String.valueOf(Main.DISCOVERY_PORT));
            }
        });

        // Multicast IP 校验 & 更新
        groupField.addActionListener(e -> {
            String s = groupField.getText().trim();
            boolean ok = false;
            if ("all-routers.mcast.net".equalsIgnoreCase(s)) {
                ok = true;
            } else {
                try {
                    InetAddress addr = InetAddress.getByName(s);
                    if (addr instanceof Inet4Address) {
                        int a = addr.getAddress()[0] & 0xFF;
                        if (a >= 224 && a <= 239) ok = true;
                    }
                } catch (Exception ex) { /* invalid */ }
            }
            if (ok) {
                Main.setMulticastGroup(s);
            } else {
                JOptionPane.showMessageDialog(this,
                        "請輸入 IPv4 多播範圍 224.0.0.0–239.255.255.255 或 all-routers.mcast.net",
                        "Multicast IP 錯誤", JOptionPane.ERROR_MESSAGE);
                groupField.setText(Main.MULTICAST_GROUP);
            }
        });
    }

    private void updateSendState() {
        boolean ok = clientPanel.getList().getSelectedValue() != null
                && filePanel.getSelectedFiles() != null; // 檢查是否有選擇檔案和客戶端
        sendPanel.getSendButton().setEnabled(ok); // 根據狀態啟用或禁用按鈕
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
        if (target == null || file == null)
            return;

        LogPanel.log("Sending files to " + target.getUserName() + "...");

        TransferCallback callback = new TransferCallback() {
            AtomicLong sentSoFar = new AtomicLong(0);
            long totalBytes;
            int lastPct = -1;

            @Override
            public void onStart(long totalBytes) {
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
            public void onStart(long totalBytes, String name) {
                this.totalBytes = totalBytes;
                sentSoFar.set(0);
                SwingUtilities.invokeLater(() -> {
                    recvPanel.getLabel().setText("Sending " + name);
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

            @Override
            public void onProgress(long bytesTransferred) {
                long cumul = sentSoFar.addAndGet(bytesTransferred);
                int pct = (int) (cumul * 100 / totalBytes);
                SwingUtilities.invokeLater(() -> recvPanel.getProgressBar().setValue(pct));
                if (pct % 10 == 0 && pct != lastPct) {
                    lastPct = pct;
                    LogPanel.log("Progress: " + pct + "% (" + formatFileSize(cumul) + ")");
                }
            }

            @Override
            public void onComplete() {
                SwingUtilities.invokeLater(() -> {
                    recvPanel.getLabel().setVisible(false);
                    recvPanel.getProgressBar().setVisible(false);
                    sendPanel.getSendButton().setEnabled(true);
                });
                LogPanel.log("File transfer complete.");
                Main.sendStatus.set(Main.SEND_STATUS.SEND_OK);
            }

            @Override
            public void onError(Exception e) {
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
            @Override
            public void onFileStart(int currentFile, int totalFiles, String fileName) {
                SwingUtilities.invokeLater(() -> {
                    String fileCountText = "File " + currentFile + " of " + totalFiles;
                    recvPanel.getFileCountLabel().setText(fileCountText);
                    log("Sending file " + currentFile + "/" + totalFiles + ": " + fileName);
                });
            }
            @Override
            public void onFileComplete(int currentFile, int totalFiles, String fileName) {
                SwingUtilities.invokeLater(() -> {
                    log("Completed file " + currentFile + "/" + totalFiles + ": " + fileName);
                });
            }
        };
        new Thread(() -> {
            try {
                FileSender sender = new FileSender(
                        target.getIPAddr(),
                        target.getTCPPort(),
                        target.getTCPPort2());
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
        String[] units = { "B", "KB", "MB", "GB" };
        double val = size;
        int idx = 0;
        while (val > 1024 && idx < units.length - 1) {
            val /= 1024;
            idx++;
        }
        return String.format("%.2f %s", val, units[idx]);
    }

    public ClientPanel getClientPanel() { // Add this getter
        return clientPanel;
    }    private static class NetworkInterfaceItem {
        private final NetworkInterface networkInterface;
        
        public NetworkInterfaceItem(NetworkInterface ni) {
            this.networkInterface = ni;
        }
        
        public NetworkInterface getNetworkInterface() {
            return networkInterface;
        }
        
        @Override
        public String toString() {
            if (networkInterface != null) {
                String displayName = networkInterface.getDisplayName();
                String ip = Main.getIPFromNetworkInterface(networkInterface);
                if (ip != null) {
                    return "🌐 " + displayName + " (" + ip + ")";
                } else {
                    return "🌐 " + displayName;
                }
            }
            return "Unknown Interface";
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            
            NetworkInterfaceItem that = (NetworkInterfaceItem) obj;
            
            return networkInterface != null ? networkInterface.equals(that.networkInterface) : that.networkInterface == null;
        }
        
        @Override
        public int hashCode() {
            return networkInterface != null ? networkInterface.hashCode() : 0;
        }
    }

}