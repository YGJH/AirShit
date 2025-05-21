package AirShit.ui;

import AirShit.SendFileGUI;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

public class LogPanel extends JPanel {
    private static JTextArea logArea;
    private JScrollPane scroll;

    private Color currentPanelBg;
    private Color currentTextPrimary;
    private Color currentBorderColor;
    private Color currentLogAreaBg; // Store the specific background for the log area

    // Constructor updated to accept logAreaBg
    public LogPanel(Color panelBg, Color textPrimary, Color borderColor, Color logAreaBg) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentBorderColor = borderColor;
        this.currentLogAreaBg = logAreaBg; // Store it

        if (logArea == null) {
            logArea = new JTextArea(6, 20);
            logArea.setEditable(false);
            logArea.setLineWrap(true);
            logArea.setWrapStyleWord(true);
        }
        if (scroll == null) {
            scroll = new JScrollPane(logArea);
        }
        styleComponents();
    }

    private void styleComponents() {
        setLayout(new BorderLayout(5, 5));
        setBackground(currentPanelBg);
        setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(currentBorderColor),
            "Status Log", TitledBorder.LEFT, TitledBorder.TOP,
            SendFileGUI.FONT_TITLE, currentTextPrimary
        ));

        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        logArea.setForeground(currentTextPrimary);
        logArea.setBackground(currentLogAreaBg); // Use the specific log area background
        logArea.setMargin(new Insets(5,5,5,5));

        scroll.setBorder(BorderFactory.createLineBorder(currentBorderColor));
        scroll.getViewport().setBackground(currentLogAreaBg); // Match log area background for viewport

        setBorder(BorderFactory.createCompoundBorder(
            getBorder(),
            BorderFactory.createEmptyBorder(5,5,5,5)
        ));
        
        removeAll();
        add(scroll, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    // updateThemeColors updated to accept logAreaBg
    public void updateThemeColors(Color panelBg, Color textPrimary, Color borderColor, Color logAreaBg) {
        this.currentPanelBg = panelBg;
        this.currentTextPrimary = textPrimary;
        this.currentBorderColor = borderColor;
        this.currentLogAreaBg = logAreaBg; // Update it
        styleComponents();
    }

    public static void log(String msg) {
        String time = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + time + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}