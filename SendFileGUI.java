package AirShit;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.Hashtable;

public class SendFileGUI extends JFrame {
    private JButton sendFileButton;
    private JList<String> userList;
    private DefaultListModel<String> listModel;
    private JProgressBar progressBar;
    
    // New controls for file selection.
    private JTextField filePathField;
    private JButton selectFileButton;
    // Holds the selected file.
    private File selectedFile;

    public SendFileGUI() {
        // Set Nimbus look and feel for a modern appearance.
        try {
            for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (Exception e) {
            // Fallback to default look and feel.
        }
        
        setTitle("Advanced File Sender");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        getContentPane().setBackground(new Color(240, 240, 240));
        ((JComponent)getContentPane()).setBorder(new EmptyBorder(15, 15, 15, 15));
        
        // Create and style the user list panel with a titled border.
        listModel = new DefaultListModel<>();
        userList = new JList<>(listModel);
        userList.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScrollPane = new JScrollPane(userList);
        listScrollPane.setBorder(new TitledBorder("Available Clients"));
        listScrollPane.setPreferredSize(new Dimension(300, 200));
        
        // File selection panel.
        JPanel filePanel = new JPanel(new BorderLayout(5, 5));
        filePanel.setOpaque(false);
        filePanel.setBorder(new TitledBorder("Select File to Transfer"));
        filePathField = new JTextField();
        filePathField.setEditable(false);
        filePathField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        selectFileButton = new JButton("Select File");
        selectFileButton.setFont(new Font("Segoe UI", Font.BOLD, 12));
        selectFileButton.setBackground(new Color(40, 167, 69));
        selectFileButton.setForeground(Color.WHITE);
        selectFileButton.setFocusPainted(false);
        selectFileButton.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        filePanel.add(filePathField, BorderLayout.CENTER);
        filePanel.add(selectFileButton, BorderLayout.EAST);
        
        // Create and style send file button.
        sendFileButton = new JButton("Send File");
        sendFileButton.setFont(new Font("Segoe UI", Font.BOLD, 14));
        sendFileButton.setBackground(new Color(0, 123, 255));
        sendFileButton.setForeground(Color.WHITE);
        sendFileButton.setFocusPainted(false);
        sendFileButton.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        
        // Setup a progress bar.
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        
        // Bottom panel for sendFileButton and progressBar.
        JPanel bottomPanel = new JPanel();
        bottomPanel.setLayout(new BoxLayout(bottomPanel, BoxLayout.Y_AXIS));
        bottomPanel.setOpaque(false);
        bottomPanel.add(sendFileButton);
        bottomPanel.add(Box.createRigidArea(new Dimension(0, 10)));
        bottomPanel.add(progressBar);
        bottomPanel.setBorder(new EmptyBorder(10, 10, 10, 10));
        
        // Main panel setup: add list and file selection above bottomPanel.
        JPanel mainPanel = new JPanel(new BorderLayout(10,10));
        mainPanel.setOpaque(false);
        mainPanel.add(listScrollPane, BorderLayout.CENTER);
        mainPanel.add(filePanel, BorderLayout.NORTH);
        
        add(mainPanel, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
        
        // Select File button action.
        selectFileButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                File file = FileChooserGUI.chooseFile();
                if (file != null) {
                    selectedFile = file;
                    filePathField.setText(file.getAbsolutePath());
                }
            }
        });
        
        // sendFileButton action: only start sending if a file is selected.
        sendFileButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String user = userList.getSelectedValue();
                if (user == null) {
                    JOptionPane.showMessageDialog(SendFileGUI.this, "Please select a receiver from the list!",
                            "No Receiver Selected", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (selectedFile == null) {
                    JOptionPane.showMessageDialog(SendFileGUI.this, "Please select a file to transfer first!",
                            "No File Selected", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // Immediately show the progress bar.
                progressBar.setValue(0);
                progressBar.setVisible(true);
                
                SwingWorker<Boolean, Void> worker = new SwingWorker<>() {
                    @Override
                    protected Boolean doInBackground() throws Exception {
                        // Call sendFileToUser and update progress when ACK is received.
                        return Main.sendFileToUser(user, selectedFile, progress -> setProgress(progress));
                    }
                    
                    @Override
                    protected void done() {
                        progressBar.setVisible(false);
                        try {
                            boolean success = get();
                            if (success) {
                                JOptionPane.showMessageDialog(SendFileGUI.this,
                                        "File sent successfully to " + user,
                                        "Success", JOptionPane.INFORMATION_MESSAGE);
                            } else {
                                JOptionPane.showMessageDialog(SendFileGUI.this,
                                        "File sending failed to " + user,
                                        "Failure", JOptionPane.ERROR_MESSAGE);
                            }
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(SendFileGUI.this,
                                    "Error: " + ex.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                };
                worker.addPropertyChangeListener(evt -> {
                    if ("progress".equals(evt.getPropertyName())) {
                        progressBar.setValue((Integer) evt.getNewValue());
                    }
                });
                worker.execute();
            }
        });        
        // Timer to refresh the user list periodically.
        Timer timer = new Timer(3000, new ActionListener() {
            public void actionPerformed(ActionEvent e ) {
                refreshUserList(Main.client);
            }
        });
        timer.start();
        
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }
    
    private void refreshUserList(Client client) {
        listModel.clear();
        Hashtable<String, Client> clients = client.clientList;
        if (clients != null) {
            for (String username : clients.keySet()) {
                listModel.addElement(username);
            }
        }
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(SendFileGUI::new);
    }
}