package AirShit;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Hashtable;
import java.util.concurrent.atomic.AtomicLong;

import AirShit.Main.SEND_STATUS;

public class SendFileGUI extends JFrame {
    private JList<Client>      clientList;
    private DefaultListModel<Client> listModel;
    private JLabel             selectedFileLabel;
    private JTextArea          logArea;
    private JButton            sendButton, refreshButton;
    private String[]           selectedFiles;
    private String             folderName;
    public  File               fatherDir;
    private Timer              refreshTimer;
    private static JLabel      textOfReceive;
    public  static JProgressBar receiveProgressBar;

    private final Color BG   = new Color(240,240,240);
    private final Color P    = new Color(41,128,185);
    private final Color A    = new Color(39,174,96);
    private final Color TXT  = new Color(52,73,94);
    private final Color LTXT = new Color(236,240,241);

    public SendFileGUI() {
        super("AirShit File Transfer");
        setSize(700,500);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        try { setIconImage(new ImageIcon(getClass().getResource("/asset/icon.jpg")).getImage()); }
        catch (Exception ignored) {}

        setupUI();
        refreshClientList();

        refreshTimer = new Timer(5_000, e -> refreshClientList());
        refreshTimer.start();
        setVisible(true);
    }

    private void setupUI() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        getContentPane().setBackground(BG);
        JPanel main = new JPanel(new BorderLayout(10,10));
        main.setBorder(BorderFactory.createEmptyBorder(15,15,15,15));
        main.setBackground(BG);

        main.add(createClientPanel(),  BorderLayout.WEST);
        main.add(createControlPanel(), BorderLayout.CENTER);

        setContentPane(main);

        clientList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) updateSendButtonState();
        });

        log("Welcome to AirShit File Transfer");
        Client me = Main.getClient();
        log("Your name: " + me.getUserName());
        log("Your IP:   " + me.getIPAddr());
    }

    private JPanel createClientPanel() {
        JPanel p = new JPanel(new BorderLayout(5,5));
        p.setBackground(BG);

        JLabel lbl = new JLabel("Available Clients");
        lbl.setFont(new Font("Microsoft JhengHei", Font.BOLD,14));
        lbl.setForeground(TXT);

        listModel  = new DefaultListModel<>();
        clientList = new JList<>(listModel);
        clientList.setCellRenderer(new ClientCellRenderer());
        clientList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        clientList.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));

        refreshButton = createStyledButton("Refresh", P);
        refreshButton.addActionListener(e -> forceRefreshClientList());

        JScrollPane sp = new JScrollPane(clientList);
        sp.setBorder(BorderFactory.createLineBorder(new Color(189,195,199)));

        p.add(lbl,    BorderLayout.NORTH);
        p.add(sp,     BorderLayout.CENTER);
        p.add(refreshButton, BorderLayout.SOUTH);
        return p;
    }

    private JPanel createControlPanel() {
        JPanel ctr = new JPanel();
        ctr.setLayout(new BoxLayout(ctr, BoxLayout.Y_AXIS));
        ctr.setBackground(BG);

        ctr.add(createFilePanel());
        ctr.add(Box.createRigidArea(new Dimension(0,15)));
        ctr.add(createSendPanel());
        ctr.add(Box.createRigidArea(new Dimension(0,15)));
        ctr.add(createReceivePanel());
        ctr.add(Box.createRigidArea(new Dimension(0,15)));
        ctr.add(createLogPanel());

        return ctr;
    }

    private JPanel createFilePanel() {
        JPanel p = new JPanel(new BorderLayout(5,5));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189,195,199)),
            "File Selection", TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Microsoft JhengHei", Font.BOLD,12), TXT
        ));

        selectedFileLabel = new JLabel("No file selected");
        selectedFileLabel.setFont(new Font("Microsoft JhengHei", Font.PLAIN,12));
        JScrollPane sp = new JScrollPane(
            selectedFileLabel,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );
        sp.setPreferredSize(new Dimension(250,100));

        JButton btn = createStyledButton("Browse Files...", P);
        btn.addActionListener(e -> selectFile());

        p.add(sp, BorderLayout.CENTER);
        p.add(btn, BorderLayout.EAST);
        return p;
    }

    private JPanel createSendPanel() {
        JPanel p = new JPanel(new BorderLayout(5,5));
        p.setBackground(BG);

        sendButton = createStyledButton("Send File", A);
        sendButton.setEnabled(false);
        sendButton.setFont(new Font("Microsoft JhengHei", Font.BOLD,14));
        sendButton.setPreferredSize(new Dimension(200,30));
        sendButton.addActionListener(e -> sendFile());

        p.add(sendButton, BorderLayout.NORTH);
        return p;
    }

    private JPanel createReceivePanel() {
        JPanel p = new JPanel(new BorderLayout(5,5));
        p.setBackground(BG);
        p.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189,195,199)),
            "進度條", TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Microsoft JhengHei",Font.BOLD,12), TXT
        ));

        textOfReceive     = new JLabel("Receiving:");
        textOfReceive.setVisible(false);
        receiveProgressBar= new JProgressBar();
        receiveProgressBar.setStringPainted(true);
        receiveProgressBar.setVisible(false);

        p.add(textOfReceive,      BorderLayout.NORTH);
        p.add(receiveProgressBar,  BorderLayout.CENTER);
        return p;
    }

    private JPanel createLogPanel() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG);
        p.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(189,195,199)),
            "Status Log", TitledBorder.LEFT, TitledBorder.TOP,
            new Font("Microsoft JhengHei",Font.BOLD,12), TXT
        ));

        logArea = new JTextArea(6,20);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas",Font.PLAIN,12));
        JScrollPane sp = new JScrollPane(logArea);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    private JButton createStyledButton(String text, Color color) {
        JButton button = new JButton(text);
        button.setBackground(color);
        button.setForeground(LTXT);
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

    private void forceRefreshClientList() {
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
            selectedFiles = new String[]{new File(fatherDir+"\\"+selectedFiles[0]).getName()};
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
        receiveProgressBar.setVisible(true);
        receiveProgressBar.setValue(0);


        System.out.println("folderName: " + folderName);
            
        TransferCallback callback = new TransferCallback() {
            AtomicLong sentSoFar = new AtomicLong(0);
            int lasPct = -1;
            long totalBytes = 0;
            @Override
            public void onStart(long totalBytes) {
                sentSoFar.set(0);
                this.totalBytes = totalBytes;
                log("totalBytes: " + totalBytes);
                SwingUtilities.invokeLater(() -> receiveProgressBar.setMaximum(100));
                SwingUtilities.invokeLater(() -> receiveProgressBar.setVisible(true));
                SwingUtilities.invokeLater(() -> receiveProgressBar.setValue(0));
            }
            @Override
            public void onProgress(long bytesTransferred) {
                long cumul = sentSoFar.addAndGet(bytesTransferred);
                SwingUtilities.invokeLater(() -> {
                    int pct = (int)(cumul*100/totalBytes);
                    receiveProgressBar.setValue(pct);
                    if (pct % 10 == 0 && pct != lasPct) {
                        lasPct = pct;
                        log("Progress: " + pct + "% (" + formatFileSize(cumul) + ")");
                    }
                });
            }
            @Override
            public void onComplete() {
                SwingUtilities.invokeLater(() -> {
                    log("File transfer complete.");
                    sendButton.setEnabled(true);
                    receiveProgressBar.setVisible(false);
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
                    receiveProgressBar.setVisible(false);
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
                panel.setBackground(P);
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