package AirShit.ui;

import AirShit.SendFileGUI; // For Font constants
import javax.swing.*;
import java.awt.*;

public class SendControlPanel extends JPanel {
    private final JButton sendButton;

    public SendControlPanel(Color bg, Color accentSuccess) {
        setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0)); // Center the button
        setBackground(bg); // Use app background for this panel
        setBorder(BorderFactory.createEmptyBorder(5,0,5,0)); // Some vertical padding

        sendButton = new JButton("Send File");
        sendButton.setFont(SendFileGUI.FONT_PRIMARY_BOLD); // Use new font
        sendButton.setBackground(accentSuccess);
        sendButton.setForeground(Color.WHITE);
        sendButton.setFocusPainted(false);
        // sendButton.putClientProperty("JButton.buttonType", "roundRect"); // Example FlatLaf property
        // sendButton.putClientProperty("JButton.focusedBorderColor", accentSuccess.darker()); // Example
        sendButton.setPreferredSize(new Dimension(150, 40)); // Make button a bit larger
        sendButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        add(sendButton);
    }

    public JButton getSendButton() {
        return sendButton;
    }
}