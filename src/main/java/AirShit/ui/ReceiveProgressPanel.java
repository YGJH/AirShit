package AirShit.ui;

import AirShit.SendFileGUI;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

public class ReceiveProgressPanel extends JPanel {
    private JLabel label; // Store to update color
    private JLabel fileCountLabel; // New label for file count display
    private JProgressBar progressBar; // Store to update color (FlatLaf handles most styling)

    // Store current colors
    private Color currentPanelBg;
    private Color currentTextPrimary;
    private Color currentBorderColor;

    public ReceiveProgressPanel(Color panelBg, Color textPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        if(textPrimary == null) {
            currentTextPrimary = SendFileGUI.TEXT_PRIMARY; // Fallback to default if null
        } else {
            currentTextPrimary = currentTextPrimary.darker(); // Darken for better contrast
        }
        this.currentBorderColor = borderColor;        // Initialize components here if not already
        if (label == null) {
            label = new JLabel("Waiting for transfer...");
        }
        if (fileCountLabel == null) {
            fileCountLabel = new JLabel(""); // Initially empty
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
        ));        label.setFont(SendFileGUI.FONT_PRIMARY_PLAIN);
        label.setForeground(currentTextPrimary);
        
        fileCountLabel.setFont(SendFileGUI.FONT_SECONDARY_PLAIN);
        fileCountLabel.setForeground(currentTextPrimary.darker());
        fileCountLabel.setHorizontalAlignment(SwingConstants.CENTER);
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
        
        // Create a panel to stack the labels vertically
        JPanel labelPanel = new JPanel();
        labelPanel.setLayout(new BoxLayout(labelPanel, BoxLayout.Y_AXIS));
        labelPanel.setBackground(currentPanelBg);
        labelPanel.add(label);
        labelPanel.add(fileCountLabel);
        
        add(labelPanel, BorderLayout.NORTH);
        add(progressBar, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void updateThemeColors(Color panelBg, Color textPrimary, Color borderColor) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        if(currentTextPrimary == null) {
            currentTextPrimary = SendFileGUI.TEXT_PRIMARY; // Fallback to default if null
        } else {
            currentTextPrimary = currentTextPrimary.darker(); // Darken for better contrast
        }

        this.currentBorderColor = borderColor;
        styleComponents();
    }

    public JLabel getLabel() {
        return label;
    }    public JProgressBar getProgressBar() {
        return progressBar;
    }
    
    public JLabel getFileCountLabel() {
        return fileCountLabel;
    }
}