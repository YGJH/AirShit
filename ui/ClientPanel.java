package AirShit.ui;

import AirShit.Client;
import AirShit.Main;
import AirShit.SendFileGUI;

import javax.swing.*;
import java.awt.*;
import java.util.Hashtable;

public class ClientPanel extends JPanel {
    private JList<Client> list;
    private DefaultListModel<Client> model;
    private JButton refreshButton;
    private JLabel titleLabel; // Store label to update its color

    // Store current colors to re-apply them
    private Color currentPanelBg;
    private Color currentTextPrimary;
    private Color currentTextSecondary;
    private Color currentAccentPrimary;
    private Color currentBorderColor;


    public ClientPanel(Color panelBg, Color textPrimary, Color textSecondary, Color accentPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentTextSecondary = textSecondary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;

        setLayout(new BorderLayout(10, 10));
        // Initial styling using passed colors
        styleComponents();
        
        refresh(); // Initial data load
    }

    private void styleComponents() {
        setBackground(currentPanelBg);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(currentBorderColor),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        ));

        if (titleLabel == null) {
            titleLabel = new JLabel("Available Clients");
        }
        titleLabel.setFont(SendFileGUI.FONT_TITLE);
        titleLabel.setForeground(currentTextPrimary);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0,0,5,0));

        if (model == null) model = new DefaultListModel<>();
        if (list == null) {
            list  = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        }
        // IMPORTANT: Re-create cell renderer with new theme colors
        list.setCellRenderer(new ClientCellRenderer(currentAccentPrimary, currentPanelBg, currentTextPrimary, currentTextSecondary));
        list.setBackground(currentPanelBg);


        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createLineBorder(currentBorderColor));
        scrollPane.getViewport().setBackground(currentPanelBg); // Ensure viewport matches

        if (refreshButton == null) {
            refreshButton = new JButton("Refresh");
            refreshButton.addActionListener(e -> refresh());
        }
        refreshButton.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        refreshButton.setBackground(currentAccentPrimary);
        refreshButton.setForeground(Color.WHITE); // Assuming white text on accent is always good
        refreshButton.setFocusPainted(false);

        // Remove old components before adding potentially new/restyled ones
        removeAll(); 
        add(titleLabel,    BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(refreshButton, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    public void updateThemeColors(Color panelBg, Color textPrimary, Color textSecondary, Color accentPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentTextSecondary = textSecondary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;
        
        // Re-apply all styles
        styleComponents();
    }

    public void refresh() {
        Main.clearClientList();
        if (model == null) model = new DefaultListModel<>();
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