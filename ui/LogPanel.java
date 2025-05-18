package AirShit.ui;

import AirShit.SendFileGUI; // For Font constants

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LogPanel extends JPanel {
    private final JTextArea logArea;

    // Corrected constructor to accept panelBg, textPrimary, and borderColor
    public LogPanel(Color panelBg, Color textPrimary, Color borderColor) {
        setLayout(new BorderLayout(5, 5));
        setBackground(panelBg);
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(borderColor), // Use borderColor
            "Status Log", TitledBorder.LEFT, TitledBorder.TOP,
            SendFileGUI.FONT_TITLE, textPrimary // Use new font and text color
        ));

        logArea = new JTextArea(6, 20);
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        logArea.setForeground(textPrimary);
        logArea.setBackground(panelBg); // Match panel background or choose a slightly different shade
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logArea.setMargin(new Insets(5,5,5,5));

        JScrollPane scroll = new JScrollPane(logArea);
        // FlatLaf usually styles scroll pane borders well, but you can force one if needed.
        scroll.setBorder(BorderFactory.createLineBorder(borderColor));
        add(scroll, BorderLayout.CENTER);

        // Add some internal padding to the LogPanel itself
        setBorder(BorderFactory.createCompoundBorder(
            getBorder(), // Keep the TitledBorder
            BorderFactory.createEmptyBorder(5,5,5,5) // Add inner padding
        ));
    }

    public void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> { // Ensure UI updates are on EDT
            logArea.append("[" + time + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}