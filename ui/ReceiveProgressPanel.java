package AirShit.ui;

import AirShit.SendFileGUI; // For Font constants

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

public class ReceiveProgressPanel extends JPanel {
    private final JLabel label;
    private final JProgressBar progressBar;

    // Updated constructor to accept borderColor
    public ReceiveProgressPanel(Color panelBg, Color textPrimary, Color borderColor) {
        setLayout(new BorderLayout(5, 5));
        setBackground(panelBg);
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(borderColor), // Use borderColor
            "Transfer Progress", TitledBorder.LEFT, TitledBorder.TOP,
            SendFileGUI.FONT_TITLE, textPrimary // Use new font and text color
        ));

        label = new JLabel("Waiting for transfer...");
        label.setFont(SendFileGUI.FONT_PRIMARY_PLAIN);
        label.setForeground(textPrimary);
        label.setVisible(true); // Keep it visible, text will change

        progressBar = new JProgressBar();
        progressBar.setFont(SendFileGUI.FONT_SECONDARY_PLAIN);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false); // Initially hidden, shown on transfer start
        progressBar.setPreferredSize(new Dimension(progressBar.getPreferredSize().width, 20)); // Make it a bit thicker

        add(label, BorderLayout.NORTH);
        add(progressBar, BorderLayout.CENTER);
        setBorder(BorderFactory.createCompoundBorder(
            getBorder(),
            BorderFactory.createEmptyBorder(5,5,5,5) // Add some internal padding
        ));
    }

    public JLabel getLabel() {
        return label;
    }

    public JProgressBar getProgressBar() {
        return progressBar;
    }
}