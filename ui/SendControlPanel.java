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
        sendButton.setPreferredSize(new Dimension(150, 40));
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

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