package AirShit.ui;

import AirShit.Client;
import AirShit.Main;
import AirShit.SendFileGUI; // To access new Font constants

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.util.Hashtable;

public class ClientPanel extends JPanel {
    private JList<Client> list;
    private DefaultListModel<Client> model;
    private JButton refreshButton;

    public ClientPanel(Color panelBg, Color textPrimary, Color textSecondary, Color accentPrimary, Color borderColor) {
        setLayout(new BorderLayout(10, 10)); // Increased gap
        setBackground(panelBg);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor), // Outer border
            BorderFactory.createEmptyBorder(10, 10, 10, 10) // Inner padding
        ));

        JLabel lbl = new JLabel("Available Clients");
        lbl.setFont(SendFileGUI.FONT_TITLE); // Use new font
        lbl.setForeground(textPrimary);
        lbl.setBorder(BorderFactory.createEmptyBorder(0,0,5,0)); // Padding below title

        model = new DefaultListModel<>();
        list  = new JList<>(model);
        list.setCellRenderer(new ClientCellRenderer(accentPrimary, panelBg, textPrimary, textSecondary)); // Pass colors
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // FlatLaf will style the scrollpane border, so explicit border on JList might not be needed
        // list.setBorder(BorderFactory.createLineBorder(borderColor));
        list.setBackground(panelBg);

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createLineBorder(borderColor)); // Border for scrollpane

        refreshButton = new JButton("Refresh");
        // FlatLaf provides good default button styling. Custom properties can be set.
        // refreshButton.putClientProperty("JButton.buttonType", "roundRect"); // Example FlatLaf property
        refreshButton.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        refreshButton.setBackground(accentPrimary);
        refreshButton.setForeground(Color.WHITE);
        refreshButton.setFocusPainted(false); // Good for modern look
        refreshButton.addActionListener(e -> refresh());

        add(lbl,    BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(refreshButton, BorderLayout.SOUTH);

        refresh();
    }

    public void refresh() {
        Main.clearClientList();
        model.clear();
        Main.multicastHello();
        try { Thread.sleep(500); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        Hashtable<String,Client> clients = Main.getClientList();
        clients.values().forEach(model::addElement);
        if (list.getModel().getSize() > 0) {
            list.setSelectedIndex(0); // Optionally select first item
        }
    }

    public JList<Client> getList() { return list; }
}