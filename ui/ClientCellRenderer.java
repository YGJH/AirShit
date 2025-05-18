package AirShit.ui;

import AirShit.Client;
import AirShit.SendFileGUI; // For Font constants

import javax.swing.*;
import java.awt.*;

public class ClientCellRenderer extends DefaultListCellRenderer {

    private final Color accentColor;
    private final Color defaultBackground;
    private final Color textPrimary;
    private final Color textSecondary;

    public ClientCellRenderer(Color accent, Color defaultBg, Color textPri, Color textSec) {
        this.accentColor = accent;
        this.defaultBackground = defaultBg;
        this.textPrimary = textPri;
        this.textSecondary = textSec;
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list,
                                                  Object value,
                                                  int index,
                                                  boolean isSelected,
                                                  boolean cellHasFocus) {
        // IMPORTANT: Call super for basic setup, then customize.
        // Or, build the component from scratch like you did.
        // For FlatLaf, often super.getListCellRendererComponent is a good start.
        // super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setOpaque(true); // Must be true for background colors to show

        Client c = (Client) value;
        JLabel nameLabel = new JLabel(c.getUserName());
        nameLabel.setFont(SendFileGUI.FONT_PRIMARY_BOLD); // Use new font
        nameLabel.setForeground(isSelected ? Color.WHITE : textPrimary);

        JLabel detailsLabel = new JLabel(c.getIPAddr() + " (" + c.getOS() + ")");
        detailsLabel.setFont(SendFileGUI.FONT_SECONDARY_PLAIN); // Use new font
        detailsLabel.setForeground(isSelected ? Color.WHITE : textSecondary);

        JPanel textPanel = new JPanel(new GridLayout(2, 1, 0, 2)); // Small vertical gap
        textPanel.setOpaque(false); // Transparent to show panel's background
        textPanel.add(nameLabel);
        textPanel.add(detailsLabel);

        JLabel iconLabel;
        try {
            // Consider making icons a bit larger or using SVG for better scaling if FlatLaf supports it well
            ImageIcon icon = new ImageIcon(this.getClass().getResource("/asset/user.png"));
            // Image scaledImage = icon.getImage().getScaledInstance(24, 24, Image.SCALE_SMOOTH); // Example scaling
            iconLabel = new JLabel(icon);
        } catch (Exception e) {
            iconLabel = new JLabel("👤"); // Fallback icon
            iconLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 20)); // Make fallback icon a bit larger
        }
        iconLabel.setBorder(BorderFactory.createEmptyBorder(0,0,0,5)); // Space between icon and text

        panel.add(iconLabel, BorderLayout.WEST);
        panel.add(textPanel, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8)); // More padding

        if (isSelected) {
            panel.setBackground(accentColor);
        } else {
            panel.setBackground(defaultBackground);
            // Alternating row colors (optional, FlatLaf might do this with a client property)
            // if (index % 2 == 0) {
            //     panel.setBackground(defaultBackground.brighter());
            // } else {
            //     panel.setBackground(defaultBackground);
            // }
        }
        return panel;
    }
}