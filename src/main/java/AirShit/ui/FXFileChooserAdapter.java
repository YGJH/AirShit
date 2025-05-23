package AirShit.ui;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;

public class FXFileChooserAdapter {
    static {
        // 確保 JavaFX 初始化（Swing 應用中必要）
        new JFXPanel(); // 初始化 JavaFX 平台
    }

    public static File showFileChooser() {
        final File[] result = new File[1];

        // 使用同步機制等待 JavaFX Thread 完成
        final Object lock = new Object();

        Platform.runLater(() -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select a File");

            File selected = fileChooser.showOpenDialog(new Stage());
            synchronized (lock) {
                result[0] = selected;
                lock.notify();
            }
        });

        try {
            synchronized (lock) {
                lock.wait(); // 等待 JavaFX Thread 完成
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return result[0];
    }
}