package AirShit.ui;

import AirShit.SendFileGUI;
import javax.swing.*;
import java.awt.*;

public class SendControlPanel extends JPanel {
    private JButton sendButton; // Keep as final if only colors change, not instance

    // Store current colors
    private Color currentAppBg;
    private Color currentAccentSuccess;

    public SendControlPanel(Color appBg, Color accentSuccess) {
        this.currentAppBg = appBg;
        this.currentAccentSuccess = accentSuccess;
        // Initialize sendButton here if it's not final
        if (sendButton == null) {
            sendButton = new JButton("Send File");
            try {
                java.net.URL sendIconURL = getClass().getResource("/asset/data-transfer.png");
                if (sendIconURL != null) {
                    ImageIcon sendIcon = new ImageIcon(sendIconURL);
                    // Scale icon to fit button nicely
                    Image scaledImg = sendIcon.getImage().getScaledInstance(20, 20, Image.SCALE_SMOOTH);
                    sendButton.setIcon(new ImageIcon(scaledImg));
                    sendButton.setHorizontalTextPosition(SwingConstants.RIGHT); // Text to the right of icon
                    sendButton.setIconTextGap(8); // Space between icon and text
                } else {
                    System.err.println("Send button icon '/asset/data-transfer.png' not found.");
                }
            } catch (Exception e) {
                System.err.println("Error loading send button icon: " + e.getMessage());
            }
        }
        styleComponents();
    }

    private void styleComponents() {
        setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
        setBackground(currentAppBg);
        setBorder(BorderFactory.createEmptyBorder(5,0,5,0));

        sendButton.setFont(SendFileGUI.FONT_PRIMARY_BOLD);
        sendButton.setBackground(currentAccentSuccess);
        sendButton.setForeground(Color.WHITE); // Assuming white text on accent is always good
        sendButton.setFocusPainted(false);
        // Adjust preferred size if icon makes it too cramped, or let layout manager decide
        // sendButton.setPreferredSize(new Dimension(150, 40)); 
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sendButton.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20)); // Increased padding for icon

        // Ensure button is added (it might be removed if panel structure changes, though not here)
        removeAll(); // Good practice if structure could change
        add(sendButton);
        revalidate();
        repaint();
    }

    public void updateThemeColors(Color appBg, Color accentSuccess) {
        this.currentAppBg = appBg;
        this.currentAccentSuccess = accentSuccess;
        styleComponents();
    }

    public JButton getSendButton() {
        return sendButton;
    }
}