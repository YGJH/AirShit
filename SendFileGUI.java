package AirShit;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
// import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.Timer;

public class SendFileGUI extends JFrame {
    private JList<ClientListItem> clientList;
    private DefaultListModel<ClientListItem> listModel;
    private JLabel selectedFileLabel;
    private JProgressBar progressBar;
    private JTextArea logArea;
    private JButton sendButton;
    private JButton refreshButton;
    private File selectedFile;
    private Timer refreshTimer;
    
    // Modern color scheme
    private final Color BACKGROUND_COLOR = new Color(240, 240, 240);
    private final Color PRIMARY_COLOR = new Color(41, 128, 185);
    private final Color ACCENT_COLOR = new Color(39, 174, 96);
    private final Color TEXT_COLOR = new Color(52, 73, 94);
    private final Color LIGHT_TEXT = new Color(236, 240, 241);
    
    public SendFileGUI() {
        setTitle("AirShit File Transfer");
        setSize(700, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Set app icon if available
        try {
            setIconImage(new ImageIcon(getClass().getResource("/asset/icon.jpg")).getImage());
        } catch (Exception e) {
            // Icon not found, continue without it
        }
        
        setupUI();
        refreshClientList();
        
        // Setup auto-refresh timer (every 5 seconds)
        refreshTimer = new Timer(5000, e -> refreshClientList());
        refreshTimer.start();
        
        setVisible(true);
    }
    
    private void setupUI() {
        // Set overall background color and look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        getContentPane().setBackground(BACKGROUND_COLOR);
        
        // Main panel with border layout
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        mainPanel.setBackground(BACKGROUND_COLOR);
        
        // === CLIENT LIST PANEL (LEFT) ===
        JPanel clientPanel = new JPanel(new BorderLayout(5, 5));
        clientPanel.setBackground(BACKGROUND_COLOR);
        
        JLabel clientsLabel = new JLabel("Available Clients");
        clientsLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        clientsLabel.setForeground(TEXT_COLOR);
        
        listModel = new DefaultListModel<>();
        clientList = new JList<>(listModel);
        clientList.setCellRenderer(new ClientCellRenderer());
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientList.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        
        JScrollPane clientScroll = new JScrollPane(clientList);
        clientScroll.setBorder(BorderFactory.createLineBorder(new Color(189, 195, 199), 1));
        
        refreshButton = createStyledButton("Refresh", PRIMARY_COLOR);
        refreshButton.addActionListener(e -> refreshClientList());
        
        clientPanel.add(clientsLabel, BorderLayout.NORTH);
        clientPanel.add(clientScroll, BorderLayout.CENTER);
        clientPanel.add(refreshButton, BorderLayout.SOUTH);
        
        // === CONTROL PANEL (RIGHT) ===
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBackground(BACKGROUND_COLOR);
        
        // File selection panel
        JPanel filePanel = new JPanel(new BorderLayout(5, 5));
        filePanel.setBackground(BACKGROUND_COLOR);
        filePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189, 195, 199)),
            "File Selection",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("Segoe UI", Font.BOLD, 12),
            TEXT_COLOR
        ));
        
        selectedFileLabel = new JLabel("No file selected");
        selectedFileLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        
        JButton browseButton = createStyledButton("Browse Files...", PRIMARY_COLOR);
        browseButton.addActionListener(e -> selectFile());
        
        filePanel.add(selectedFileLabel, BorderLayout.CENTER);
        filePanel.add(browseButton, BorderLayout.EAST);
        
        // Send panel
        JPanel sendPanel = new JPanel(new BorderLayout(5, 5));
        sendPanel.setBackground(BACKGROUND_COLOR);
        
        sendButton = createStyledButton("Send File", ACCENT_COLOR);
        sendButton.setEnabled(false);
        sendButton.addActionListener(e -> sendFile());
        sendButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        sendButton.setPreferredSize(new Dimension(200, 40));
        
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        
        sendPanel.add(sendButton, BorderLayout.NORTH);
        sendPanel.add(progressBar, BorderLayout.SOUTH);
        
        // Log area
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBackground(BACKGROUND_COLOR);
        logPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189, 195, 199)),
            "Status Log",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("Segoe UI", Font.BOLD, 12),
            TEXT_COLOR
        ));
        
        logArea = new JTextArea(6, 20);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        JScrollPane logScroll = new JScrollPane(logArea);
        
        logPanel.add(logScroll, BorderLayout.CENTER);
        
        // Add components to control panel
        controlPanel.add(filePanel);
        controlPanel.add(Box.createRigidArea(new Dimension(0, 15)));
        controlPanel.add(sendPanel);
        controlPanel.add(Box.createRigidArea(new Dimension(0, 15)));
        controlPanel.add(logPanel);
        
        // Add panels to main panel
        mainPanel.add(clientPanel, BorderLayout.WEST);
        mainPanel.add(controlPanel, BorderLayout.CENTER);
        
        // Add main panel to frame
        setContentPane(mainPanel);
        
        // Listen for selection changes
        clientList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateSendButtonState();
            }
        });
        
        // Initial log message
        log("Welcome to AirShit File Transfer");
        Client currentClient = Main.getClient();
        log("Your name: " + currentClient.getUserName());
        log("Your IP: " + currentClient.getIPAddr());
        log("Ready to send files. Please select a client and a file.");
    }
    
    private JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(LIGHT_TEXT);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        
        // Add hover effect
        button.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                button.setBackground(color.darker());
            }
            
            @Override
            public void mouseExited(MouseEvent e) {
                button.setBackground(color);
            }
        });
        
        return button;
    }
    
    private void refreshClientList() {
        listModel.clear();
        Hashtable<String, Client> clients = Main.getClientPorts();
        
        for (Map.Entry<String, Client> entry : clients.entrySet()) {
            Client client = entry.getValue();
            println(client.getUserName() + ":" + client.getUDPPort());
            listModel.addElement(new ClientListItem(client));
        }
        
        updateSendButtonState();
        log("Client list refreshed. Found " + clients.size() + " clients.");
    }
    
    private void selectFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select File to Send");
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedFile = chooser.getSelectedFile();
            selectedFileLabel.setText(selectedFile.getName() + " (" + formatFileSize(selectedFile.length()) + ")");
            updateSendButtonState();
            log("File selected: " + selectedFile.getName());
        }
    }
    
    private void sendFile() {
        if (clientList.getSelectedValue() == null || selectedFile == null) {
            return;
        }
        
        Client selectedClient = clientList.getSelectedValue().client;
        log("Sending file to " + selectedClient.getUserName() + "...");
        
        // Disable send button during transfer
        sendButton.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setVisible(true);
        
        // Start a background thread for file transfer
        new Thread(() -> {
            try {
                // Simulate progress updates (in a real implementation, you'd get actual progress)
                for (int i = 0; i <= 100; i += 10) {
                    final int progress = i;
                    SwingUtilities.invokeLater(() -> progressBar.setValue(progress));
                    Thread.sleep(150);
                }
                
                // Do the actual file send
                boolean success = Main.sendFileToUser(selectedClient.getUserName(), selectedFile);
                
                // Update UI based on result
                SwingUtilities.invokeLater(() -> {
                    if (success) {
                        log("File sent successfully to " + selectedClient.getUserName());
                    } else {
                        log("Failed to send file to " + selectedClient.getUserName());
                    }
                    progressBar.setVisible(false);
                    updateSendButtonState();
                });
                
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    log("Error: " + e.getMessage());
                    progressBar.setVisible(false);
                    updateSendButtonState();
                });
            }
        }).start();
    }
    
    private void updateSendButtonState() {
        boolean clientSelected = clientList.getSelectedValue() != null;
        boolean fileSelected = selectedFile != null;
        sendButton.setEnabled(clientSelected && fileSelected);
    }
    
    private void log(String message) {
        String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
        logArea.append("[" + timestamp + "] " + message + "\n");
        // Auto-scroll to bottom
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
    
    private String formatFileSize(long size) {
        final String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double fileSize = size;
        
        while (fileSize > 1024 && unitIndex < units.length - 1) {
            fileSize /= 1024;
            unitIndex++;
        }
        
        return String.format("%.2f %s", fileSize, units[unitIndex]);
    }
    
    // Helper class to represent client entries in the list
    private static class ClientListItem {
        Client client;
        
        public ClientListItem(Client client) {
            this.client = client;
        }
        
        @Override
        public String toString() {
            return client.getUserName();
        }
    }
    
    // Custom renderer for client list items
    private class ClientCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            
            JPanel panel = new JPanel(new BorderLayout(10, 0));
            panel.setOpaque(true);
            
            if (isSelected) {
                panel.setBackground(PRIMARY_COLOR);
                panel.setForeground(LIGHT_TEXT);
            } else {
                panel.setBackground(list.getBackground());
                panel.setForeground(list.getForeground());
            }
            
            ClientListItem item = (ClientListItem)value;
            
            JLabel nameLabel = new JLabel(item.client.getUserName());
            nameLabel.setFont(new Font("Segoe UI", Font.BOLD, 13));
            
            JLabel detailsLabel = new JLabel(item.client.getIPAddr() + " (" + item.client.getOS() + ")");
            detailsLabel.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            
            JPanel textPanel = new JPanel(new GridLayout(2, 1));
            textPanel.setOpaque(false);
            textPanel.add(nameLabel);
            textPanel.add(detailsLabel);
            
            if (isSelected) {
                nameLabel.setForeground(LIGHT_TEXT);
                detailsLabel.setForeground(LIGHT_TEXT);
            } else {
                nameLabel.setForeground(TEXT_COLOR);
                detailsLabel.setForeground(TEXT_COLOR.brighter());
            }
            
            // Add icon
            JLabel iconLabel;
            try {
                iconLabel = new JLabel(new ImageIcon(getClass().getResource("/asset/user.png")));

            } catch (Exception e) {
                iconLabel = new JLabel("👤"); // Fallback text if icon not found
            }

            panel.add(iconLabel, BorderLayout.WEST);
            panel.add(textPanel, BorderLayout.CENTER);
            
            panel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            return panel;
        }
    }
    public void println(String str) {
        System.out.println(str);
    }
}