package AirShit;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.Hashtable;

public class SendFileGUI extends JFrame {
    private JButton sendFileButton;
    private JList<String> userList;
    private DefaultListModel<String> listModel;
    
    public SendFileGUI() {
        super("Send File GUI");
        setLayout(new BorderLayout());
        
        // Create list model & JList for connected users
        listModel = new DefaultListModel<>();
        userList = new JList<>(listModel);
        userList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScrollPane = new JScrollPane(userList);
        
        // Create send file button
        sendFileButton = new JButton("Send File");
        
        // Layout: list on center and button at bottom
        add(listScrollPane, BorderLayout.CENTER);
        add(sendFileButton, BorderLayout.SOUTH);
        
        // Button action: check selected user, choose file and call sendFileToUser
        sendFileButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String selectedUser = userList.getSelectedValue();
                if (selectedUser == null) {
                    JOptionPane.showMessageDialog(SendFileGUI.this, "Please select a receiver from the list!",
                            "No Receiver Selected", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                File file = FileChooserGUI.chooseFile();
                if (file == null) {
                    JOptionPane.showMessageDialog(SendFileGUI.this, "No file was chosen!", 
                            "File Not Selected", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                
                // Call the FileShare static method to send the file
                boolean success = FileShare.sendFileToUser(selectedUser, file);
                if (success) {
                    JOptionPane.showMessageDialog(SendFileGUI.this, "File sent successfully to " + selectedUser,
                            "Success", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(SendFileGUI.this, "File sending failed to " + selectedUser,
                            "Failure", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
        
        // Timer to refresh the connected users (every 3 seconds)
        Timer timer = new Timer(3000, new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                refreshUserList();
            }
        });
        timer.start();
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        setLocationRelativeTo(null);
        setVisible(true);
    }
    
    private void refreshUserList() {
        listModel.clear();
        Hashtable<String, Client> clients = FileShare.getClientPorts();
        if(clients != null) {
            for(String username : clients.keySet()){
                listModel.addElement(username);
            }
        }
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SendFileGUI());
    }
}