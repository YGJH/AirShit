package AirShit;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicLong;

public class SendFileGUI extends JFrame {
    private JList<Client> clientList;
    private DefaultListModel<Client> listModel;
    private JLabel selectedFileLabel;
    private JProgressBar sendProgressBar;
    private JTextArea logArea;
    private JButton sendButton;
    private JButton refreshButton;
    private File[] selectedFiles;
    private Timer refreshTimer;

    private static JLabel textOfReceive;
    private static JProgressBar receiveProgressBar;

    private final Color BACKGROUND_COLOR = new Color(240, 240, 240);
    private final Color PRIMARY_COLOR    = new Color(41, 128, 185);
    private final Color ACCENT_COLOR     = new Color(39, 174, 96);
    private final Color TEXT_COLOR       = new Color(52, 73, 94);
    private final Color LIGHT_TEXT       = new Color(236, 240, 241);

    public SendFileGUI() {
        setTitle("AirShit File Transfer");
        setSize(700, 500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // optional app icon
        try {
            setIconImage(new ImageIcon(getClass().getResource("/asset/icon.jpg")).getImage());
        } catch (Exception ignored) {}

        setupUI();
        refreshClientList();

        // auto‐refresh every 50ms
        refreshTimer = new Timer(50, e -> refreshClientList());
        refreshTimer.start();

        setVisible(true);
    }

    private void setupUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        getContentPane().setBackground(BACKGROUND_COLOR);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        mainPanel.setBackground(BACKGROUND_COLOR);

        // --- CLIENT LIST ---
        JPanel clientPanel = new JPanel(new BorderLayout(5, 5));
        clientPanel.setBackground(BACKGROUND_COLOR);

        JLabel clientsLabel = new JLabel("Available Clients");
        clientsLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        clientsLabel.setForeground(TEXT_COLOR);

        listModel  = new DefaultListModel<>();
        clientList = new JList<>(listModel);
        clientList.setCellRenderer(new ClientCellRenderer());
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientList.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JScrollPane clientScroll = new JScrollPane(clientList);
        clientScroll.setBorder(BorderFactory.createLineBorder(new Color(189, 195, 199)));

        refreshButton = createStyledButton("Refresh", PRIMARY_COLOR);
        refreshButton.addActionListener(e -> ForcerefreshClientList());

        clientPanel.add(clientsLabel, BorderLayout.NORTH);
        clientPanel.add(clientScroll,  BorderLayout.CENTER);
        clientPanel.add(refreshButton,  BorderLayout.SOUTH);

        // --- CONTROL PANEL ---
        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BoxLayout(controlPanel, BoxLayout.Y_AXIS));
        controlPanel.setBackground(BACKGROUND_COLOR);

        // file chooser
        JPanel filePanel = new JPanel(new BorderLayout(5,5));
        filePanel.setBackground(BACKGROUND_COLOR);
        filePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189,195,199)),
            "File Selection", TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Segoe UI", Font.BOLD, 12), TEXT_COLOR
        ));
        selectedFileLabel = new JLabel("No file selected");
        selectedFileLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        JButton browseButton = createStyledButton("Browse Files...", PRIMARY_COLOR);
        browseButton.addActionListener(e -> selectFile());
        filePanel.add(selectedFileLabel, BorderLayout.CENTER);
        filePanel.add(browseButton,      BorderLayout.EAST);

        // send panel
        JPanel sendPanel = new JPanel(new BorderLayout(5,5));
        sendPanel.setBackground(BACKGROUND_COLOR);
        sendButton = createStyledButton("Send File", ACCENT_COLOR);
        sendButton.setEnabled(false);
        sendButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        sendButton.setPreferredSize(new Dimension(200, 40));
        sendButton.addActionListener(e -> sendFile());

        sendProgressBar = new JProgressBar();
        sendProgressBar.setStringPainted(true);
        sendProgressBar.setVisible(false);

        sendPanel.add(sendButton,       BorderLayout.NORTH);
        sendPanel.add(sendProgressBar,  BorderLayout.SOUTH);

        // receive panel
        JPanel recvPanel = new JPanel(new BorderLayout(5,5));
        recvPanel.setBackground(BACKGROUND_COLOR);
        recvPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189,195,199)),
            "Receive Progress", TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Segoe UI", Font.BOLD, 12), TEXT_COLOR
        ));
        textOfReceive = new JLabel("Receiving:");
        textOfReceive.setVisible(false);
        receiveProgressBar = new JProgressBar();
        receiveProgressBar.setStringPainted(true);
        receiveProgressBar.setVisible(false);
        recvPanel.add(textOfReceive,        BorderLayout.NORTH);
        recvPanel.add(receiveProgressBar,   BorderLayout.CENTER);

        // log panel
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBackground(BACKGROUND_COLOR);
        logPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189,195,199)),
            "Status Log", TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Segoe UI", Font.BOLD, 12), TEXT_COLOR
        ));
        logArea = new JTextArea(6,20);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        logPanel.add(logScroll, BorderLayout.CENTER);

        controlPanel.add(filePanel);
        controlPanel.add(Box.createRigidArea(new Dimension(0,15)));
        controlPanel.add(sendPanel);
        controlPanel.add(Box.createRigidArea(new Dimension(0,15)));
        controlPanel.add(recvPanel);
        controlPanel.add(Box.createRigidArea(new Dimension(0,15)));
        controlPanel.add(logPanel);

        mainPanel.add(clientPanel,  BorderLayout.WEST);
        mainPanel.add(controlPanel, BorderLayout.CENTER);
        setContentPane(mainPanel);

        // on selection change
        clientList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateSendButtonState();
        });

        // initial log
        log("Welcome to AirShit File Transfer");
        Client me = Main.getClient();
        log("Your name: " + me.getUserName());
        log("Your IP:   " + me.getIPAddr());
    }

    private JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(LIGHT_TEXT);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, 12));
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        button.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                button.setBackground(color.darker());
            }
            public void mouseExited(MouseEvent e) {
                button.setBackground(color);
            }
        });
        return button;
    }

    private void ForcerefreshClientList() {
        Main.multicastHello();
        refreshClientList();
    }
    private void refreshClientList() {
        // snapshot current selection so we can restore it after updating
        Client previousSelection = clientList.getSelectedValue();

        Hashtable<String, Client> clients = Main.getClientList();

        // 1) remove any clients that have disappeared
        for (int i = listModel.getSize() - 1; i >= 0; i--) {
            Client c = listModel.getElementAt(i);
            if (!clients.containsKey(c.getUserName())) {
                listModel.remove(i);
            }
        }

        // 2) add any new clients
        for (Client c : clients.values()) {
            if (!listModel.contains(c)) {
                // check if the client is already in the list
                boolean alreadyInList = false;
                for (int i = 0; i < listModel.getSize(); i++) {
                    Client client = listModel.getElementAt(i);
                    if (client.getUserName().equals(c.getUserName())) {
                        alreadyInList = true;
                        break;
                    }
                }
                if (!alreadyInList) {
                    // add the new client to the list model
                    listModel.addElement(c);
                }

            }
        }

        // 3) restore the previous selection if still present
        if (previousSelection != null && listModel.contains(previousSelection)) {
            clientList.setSelectedValue(previousSelection, true);
        }

        updateSendButtonState();
    }

    private void selectFile() {
        selectedFiles = FolderSelector.selectFolderAndListFiles(null);
        if (selectedFiles != null && selectedFiles.length > 0) {
            StringBuilder sb = new StringBuilder("<html>");
            for (File f: selectedFiles) {
                sb.append(f.getName())
                  .append(" (")
                  .append(formatFileSize(f.length()))
                  .append(")<br>");
            }
            sb.append("</html>");
            selectedFileLabel.setText(sb.toString());
        } else {
            selectedFileLabel.setText("No file selected");
        }
        updateSendButtonState();
    }

    private void sendFile() {
        if (clientList.getSelectedValue()==null ||
            selectedFiles==null || selectedFiles.length==0) return;

        Client target = clientList.getSelectedValue();
        log("Sending files to " + target.getUserName() + "...");
        sendButton.setEnabled(false);
        sendProgressBar.setVisible(true);
        sendProgressBar.setValue(0);

        long totalSize = Arrays.stream(selectedFiles)
                               .mapToLong(File::length)
                               .sum();
        AtomicLong sentSoFar = new AtomicLong(0);

        TransferCallback callback = new TransferCallback() {
            @Override
            public void onStart(long totalBytes) {
                SwingUtilities.invokeLater(() -> sendProgressBar.setMaximum((int)totalBytes));
            }
            @Override
            public void onProgress(long bytesTransferred) {
                long cumul = sentSoFar.addAndGet(bytesTransferred);
                SwingUtilities.invokeLater(() -> {
                    sendProgressBar.setValue((int)cumul);
                    int pct = (int)(cumul*100/totalSize);
                    if (pct % 10 == 0) {
                        log("Progress: " + pct + "% (" + formatFileSize(cumul) + ")");
                    }
                });
            }
            @Override
            public void onError(Exception e) {
                SwingUtilities.invokeLater(() -> {
                    log("Error: " + e.getMessage());
                    sendButton.setEnabled(true);
                    sendProgressBar.setVisible(false);
                });
            }
        };

        new Thread(() -> {
            FileSender sender = new FileSender(
                target.getIPAddr(),
                target.getTCPPort()
            );
            try {
                sender.sendFiles(
                    selectedFiles,
                    Main.getClient().getUserName(),
                    FolderSelector.getFolderName(),
                    callback
                );
            } catch (Exception e) {
                callback.onError(e);
            }
        }, "send-thread").start();
    }

    private void updateSendButtonState() {
        boolean ok = clientList.getSelectedValue()!=null
                  && selectedFiles!=null && selectedFiles.length>0;
        sendButton.setEnabled(ok);
    }

    private void log(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logArea.append("[" + t + "] " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private String formatFileSize(long size) {
        String[] units = {"B","KB","MB","GB"};
        int idx = 0;
        double val = size;
        while (val>1024 && idx<units.length-1) {
            val /= 1024;
            idx++;
        }
        return String.format("%.2f %s", val, units[idx]);
    }

    public static void receiveFileProgress(int percent) {
        SwingUtilities.invokeLater(() -> {
            textOfReceive.setVisible(true);
            receiveProgressBar.setVisible(true);
            receiveProgressBar.setValue(percent);
        });
    }

    private class ClientCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list,
                                                      Object value,
                                                      int index,
                                                      boolean isSelected,
                                                      boolean cellHasFocus) {
            JPanel panel = new JPanel(new BorderLayout(10,0));
            panel.setOpaque(true);
            if (isSelected) {
                panel.setBackground(PRIMARY_COLOR);
            } else {
                panel.setBackground(list.getBackground());
            }
            Client c = (Client)value;
            JLabel name = new JLabel(c.getUserName());
            name.setFont(new Font("Segoe UI", Font.BOLD, 13));
            JLabel details = new JLabel(c.getIPAddr() + " (" + c.getOS() + ")");
            details.setFont(new Font("Segoe UI", Font.PLAIN, 10));
            JPanel texts = new JPanel(new GridLayout(2,1));
            texts.setOpaque(false);
            texts.add(name);
            texts.add(details);
            panel.add(texts, BorderLayout.CENTER);

            JLabel icon;
            try {
                icon = new JLabel(new ImageIcon(getClass().getResource("/asset/user.png")));
            } catch (Exception e) {
                icon = new JLabel("👤");
            }
            panel.add(icon, BorderLayout.WEST);
            panel.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));
            return panel;
        }
    }
}