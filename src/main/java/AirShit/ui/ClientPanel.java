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
    private JLabel titleLabel;

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
        styleComponents();

        discoverAndRefreshList(); // Initial discovery and refresh
    }

    private void styleComponents() {
        setBackground(currentPanelBg);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(currentBorderColor),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)));

        if (titleLabel == null) {
            titleLabel = new JLabel("Available Clients");
        }
        titleLabel.setFont(SendFileGUI.FONT_TITLE);
        titleLabel.setForeground(currentTextPrimary);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        if (model == null)
            model = new DefaultListModel<>();
        if (list == null) {
            list = new JList<>(model);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

            // 添加 MouseListener 以在點擊空白區域時取消選擇
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    int index = list.locationToIndex(e.getPoint());
                    if (index == -1) { // 檢查是否點擊在空白區域
                        list.clearSelection(); // 清除選擇
                    } else {
                        Rectangle cellBounds = list.getCellBounds(index, index);
                        if (cellBounds == null || !cellBounds.contains(e.getPoint())) {
                            list.clearSelection(); // 點擊在項目範圍外也清除選擇
                        }
                    }
                }
            });
        }
        list.setCellRenderer(
                new ClientCellRenderer(currentAccentPrimary, currentPanelBg, currentTextPrimary, currentTextSecondary));
        list.setBackground(currentPanelBg);

        JScrollPane scrollPane = new JScrollPane(list);
        scrollPane.setBorder(BorderFactory.createLineBorder(currentBorderColor));
        scrollPane.getViewport().setBackground(currentPanelBg);

        if (refreshButton == null) {
            refreshButton = new JButton("Refresh");
            refreshButton.addActionListener(e -> discoverAndRefreshList()); // Button triggers full discovery
        }
        refreshButton.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        refreshButton.setBackground(currentAccentPrimary);
        refreshButton.setForeground(Color.WHITE);
        refreshButton.setFocusPainted(false);
        refreshButton.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
        refreshButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshButton.setToolTipText("Refresh the list of available clients"); // Added tooltip

        removeAll();
        add(titleLabel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(refreshButton, BorderLayout.SOUTH);
        revalidate();
        repaint();
    }

    public void updateThemeColors(Color panelBg, Color textPrimary, Color textSecondary, Color accentPrimary,
            Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentTextSecondary = textSecondary;
        this.currentAccentPrimary = accentPrimary;
        this.currentBorderColor = borderColor;
        styleComponents();
    }

    /**
     * Initiates a client discovery process and schedules a GUI update.
     * Clears Main's client list and sends a multicast hello.
     */
    public void discoverAndRefreshList() {
        // System.out.println("ClientPanel: discoverAndRefreshList() called. Clearing
        // Main's list and multicasting hello.");
        Main.clearClientList(); // User-initiated refresh should clear and re-discover from Main's perspective
        if (model == null)
            model = new DefaultListModel<>();
        model.clear(); // Clear the GUI list immediately
        Main.multicastHello(); // Send out discovery packets

        // Schedule a GUI update after a short delay to allow Main to populate its list
        // from responses or existing knowledge.
        Timer timer = new Timer(50, e -> SwingUtilities.invokeLater(this::refreshGuiListOnly)); // 0.5 second delay
        timer.setRepeats(false);
        timer.start();
    }

    /**
     * Refreshes the GUI list strictly from Main.getClientList().
     * This method should be called when Main detects a change in its client list.
     */
    public void refreshGuiListOnly() {
        // System.out.println("ClientPanel: refreshGuiListOnly() called.");
        if (model == null)
            model = new DefaultListModel<>();
        model.clear(); // Clear current GUI list before repopulating

        Hashtable<String, Client> clients = Main.getClientList();

        if (clients != null) {
            // System.out.println("ClientPanel (refreshGuiListOnly): Fetched " +
            // clients.size() + " clients from Main.");
            clients.values().forEach(c -> {
                // Ensure we are not adding self to the list if Main.getClientList() might
                // contain it
                if (Main.getClient() != null && c.getIPAddr().equals(Main.getClient().getIPAddr())
                        && c.getUserName().equals(Main.getClient().getUserName())) {
                    // System.out.println("ClientPanel (refreshGuiListOnly): Skipping self: " +
                    // c.getUserName());
                    return;
                }
                // System.out.println("ClientPanel (refreshGuiListOnly): Adding client to model:
                // " + c.getUserName() + " - " + c.getIPAddr());
                model.addElement(c);
            });
        } else {
            // System.out.println("ClientPanel (refreshGuiListOnly): Fetched null client
            // list from Main.");
        }

        if (list != null && list.getModel().getSize() > 0) {
            // System.out.println("ClientPanel (refreshGuiListOnly): Model size after
            // adding: " + model.getSize() + ".");
            // Consider not auto-selecting: list.setSelectedIndex(0);
        } else if (list != null) {
            // System.out.println("ClientPanel (refreshGuiListOnly): Model is empty after
            // refresh. List model size: " + list.getModel().getSize());
        }
        if (list != null) {
            list.revalidate();
            list.repaint();
        }
    }

    public JList<Client> getList() {
        return list;
    }
}