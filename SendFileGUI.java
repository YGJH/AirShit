package AirShit;

import javax.swing.*;
import javax.swing.border.*;

import AirShit.Main.SEND_STATUS;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
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
    private String[] selectedFiles;
    private String folderName;
    public File fatherDir;
    private Timer refreshTimer;
    private JScrollPane fiScrollPane;
    private static JLabel textOfReceive;
    public static JProgressBar receiveProgressBar;

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
        refreshTimer = new Timer(5000, e -> refreshClientList());
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

        // wrap the label in a scroll pane so long file lists can scroll
        JScrollPane fileScrollPane = new JScrollPane(
            selectedFileLabel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        fileScrollPane.setPreferredSize(new Dimension(250, 100));
        filePanel.add(fileScrollPane, BorderLayout.CENTER);

        JButton browseButton = createStyledButton("Browse Files...", PRIMARY_COLOR);
        browseButton.addActionListener(e -> selectFile());
        filePanel.add(browseButton,      BorderLayout.EAST);

        // send panel
        JPanel sendPanel = new JPanel(new BorderLayout(5,5));
        sendPanel.setBackground(BACKGROUND_COLOR);
        sendButton = createStyledButton("Send File", ACCENT_COLOR);
        sendButton.setEnabled(false);
        sendButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        sendButton.setPreferredSize(new Dimension(200, 30));
        sendButton.addActionListener((e) -> sendFile());

        sendProgressBar = new JProgressBar();
        sendProgressBar.setStringPainted(true);
        // you could also use a titled border, e.g.:
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
        Main.clearClientList();
        listModel.clear();
        Main.multicastHello();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        refreshClientList();
    }
    public void refreshClientList() {

        Hashtable<String, Client> clients = Main.getClientList();
        for(int i = listModel.size()-1; i>=0; i--) {
            Client c = listModel.getElementAt(i);
            // check if the client is still in the list
            if(clients.get(c.getUserName()) == null) {
                listModel.remove(i);
            } else if(!Client.check(c, clients.get(c.getUserName()))) {
                listModel.remove(i);
            }
        }
        for (Client c : clients.values()) {
            boolean found = false;
            for(int i = 0; i < listModel.size(); i++) {
                Client c2 = listModel.getElementAt(i);
                if (Client.check(c, c2)) {
                    found = true;
                }
            }
            if(!found) {
                listModel.addElement(c);
            }
        }

        updateSendButtonState();
    }

    private void selectFile() {
        File selectedFile;
        selectedFile = FolderSelector.selectFolderOrFiles(null);
        fatherDir = selectedFile.getParentFile();
        folderName = selectedFile.getName();
        if (selectedFile == null) {
            log("No file selected");
            return;
        }

        if (selectedFile.isDirectory()) {
            selectedFiles = FolderSelector.listFilesRecursivelyWithRelativePaths(selectedFile);
            selectedFileLabel.setText("Selected folder: " + selectedFile.getAbsolutePath());

            StringBuilder sb = new StringBuilder();
            sb.append("<html><body>");
            sb.append("<h3>Selected files:</h3>");
            for (String file : selectedFiles) {
                sb.append(file).append("<br>");
            }
            sb.append("</body></html>");
            selectedFileLabel.setText(sb.toString());
            selectedFileLabel.setFont(new Font("Microsoft JhengHei", Font.PLAIN, 12));
        } else {
            selectedFiles = new String[]{selectedFile.getAbsolutePath()};
            selectedFileLabel.setText("Selected file: " + selectedFile.getAbsolutePath());
            StringBuilder sb = new StringBuilder();
            sb.append("<html><body>");
            sb.append("<h3>Selected file:</h3>");
            sb.append(selectedFile.getAbsolutePath());
            sb.append("</body></html>");
            selectedFileLabel.setText(sb.toString());
            // set chinese font
            selectedFileLabel.setFont(new Font("Microsoft JhengHei", Font.PLAIN, 12));
        }

        updateSendButtonState();
    }


    private void sendFile() {
        if (clientList.getSelectedValue() == null ||
            selectedFiles == null) return;

        Client target = clientList.getSelectedValue();
        log("Sending files to " + target.getUserName() + "...");
        sendButton.setEnabled(false);
        sendProgressBar.setVisible(true);
        sendProgressBar.setValue(0);

        final long totalSize = Arrays.stream(selectedFiles)
                               .mapToLong(file -> {
                                   File f = new File(fatherDir+"\\"+folderName+"\\"+ file);
                                   if (f.exists()) {
                                       return f.length();
                                   } else {
                                       log("File not found: " + file);
                                       return 0;
                                   }
                               })
                               .sum();

        System.out.println("folderName: " + folderName);
            
        TransferCallback callback = new TransferCallback() {
            AtomicLong sentSoFar = new AtomicLong(0);
            @Override
            public void onStart(long totalBytes) {
                sentSoFar.set(0);
                SwingUtilities.invokeLater(() -> sendProgressBar.setMaximum(100));
                SwingUtilities.invokeLater(() -> sendProgressBar.setVisible(true));
                SwingUtilities.invokeLater(() -> sendProgressBar.setValue(0));
            }
            @Override
            public void onProgress(long bytesTransferred) {
                long cumul = sentSoFar.addAndGet(bytesTransferred);
                SwingUtilities.invokeLater(() -> {
                    int pct = (int)(cumul*100/totalSize);
                    sendProgressBar.setValue(pct);
                    if (pct % 10 == 0) {
                        log("Progress: " + pct + "% (" + formatFileSize(cumul) + ")");
                    }
                });
            }
            @Override
            public void onComplete() {
                SwingUtilities.invokeLater(() -> {
                    log("File transfer complete.");
                    sendButton.setEnabled(true);
                    sendProgressBar.setVisible(false);
                });    
            }
            @Override
            public void onError(Exception e) {
                SwingUtilities.invokeLater(() -> {
                    // 先印到 log 裡
                    log("Error: " + e);              // 印 e.toString() 而不是 e.getMessage()
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    log(sw.toString());               // 把 stack‐trace 也印進 log
                    sendButton.setEnabled(true);
                    sendProgressBar.setVisible(false);
                });
            }
        };

        new Thread(() -> {
            FileSender sender = new FileSender(
                target.getIPAddr(),
                target.getTCPPort(),
                fatherDir.getAbsolutePath()
            );
            try {
                Main.sendStatus.set(SEND_STATUS.SEND_WAITING);
                sender.sendFiles(
                    selectedFiles,
                    Main.getClient().getUserName(),
                    folderName,
                    callback
                );
            } catch (Exception e) {
                callback.onError(e);
            }
            Main.sendStatus.set(SEND_STATUS.SEND_OK);

        }, "send-thread").start();
    }

    private void updateSendButtonState() {
        boolean ok = clientList.getSelectedValue()!=null
                  && selectedFiles!=null && selectedFiles.length>0;
        sendButton.setEnabled(ok);
    }

    public void log(String msg) {
        String t = new SimpleDateFormat("HH:mm:ss").format(new Date());
        logArea.append("\r[" + t + "] " + msg + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    public static String formatFileSize(long size) {
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