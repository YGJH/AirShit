package AirShit.ui;

import AirShit.SendFileGUI;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

public class ReceiveProgressPanel extends JPanel {
    private JLabel label; // Store to update color
    private JProgressBar progressBar; // Store to update color (FlatLaf handles most styling)

    // Store current colors
    private Color currentPanelBg;
    private Color currentTextPrimary;
    private Color currentBorderColor;

    public ReceiveProgressPanel(Color panelBg, Color textPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentBorderColor = borderColor;

        // Initialize components here if not already
        if (label == null) {
            label = new JLabel("Waiting for transfer...");
        }
        if (progressBar == null) {
            progressBar = new JProgressBar();
        }
        styleComponents();
    }

    private void styleComponents() {
        setLayout(new BorderLayout(5, 5));
        setBackground(currentPanelBg);
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(currentBorderColor),
            "Transfer Progress", TitledBorder.LEFT, TitledBorder.TOP,
            SendFileGUI.FONT_TITLE, currentTextPrimary
        ));

        label.setFont(SendFileGUI.FONT_PRIMARY_PLAIN);
        label.setForeground(currentTextPrimary);
        // Add an icon to the label (optional, could be dynamic based on state)
        // Example: label.setIcon(new ImageIcon(getClass().getResource("/asset/info.png")));

        progressBar.setFont(SendFileGUI.FONT_SECONDARY_PLAIN);
        progressBar.setStringPainted(true); // Ensure string is painted
        progressBar.setPreferredSize(new Dimension(progressBar.getPreferredSize().width, 22)); // Slightly taller
        // Customize progress bar colors (FlatLaf might override some of these)
        // progressBar.setForeground(SendFileGUI.ACCENT_SUCCESS); // Color for the progress itself
        // progressBar.setBackground(currentPanelBg.brighter()); // Background of the bar track

        // Add some internal padding to the panel itself
        setBorder(BorderFactory.createCompoundBorder(
            getBorder(), // Keep the TitledBorder
            BorderFactory.createEmptyBorder(5,5,5,5)
        ));
        
        removeAll(); // Good practice
        add(label, BorderLayout.NORTH);
        add(progressBar, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void updateThemeColors(Color panelBg, Color textPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentBorderColor = borderColor;
        styleComponents();
    }

    public JLabel getLabel() {
        return label;
    }

    public JProgressBar getProgressBar() {
        return progressBar;
    }
}