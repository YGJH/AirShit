package AirShit.ui;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.stage.Stage;

import java.io.File;

public class FXFileChooserAdapter {
    static {
        new JFXPanel(); // 確保 JavaFX 平台初始化
        Platform.setImplicitExit(false); // 防止 JavaFX 平台在所有視窗關閉後退出
    }

    public static File showFileChooser() {
        final File[] result = new File[1];

        // 使用同步機制等待 JavaFX Thread 完成
        final Object lock = new Object();

        Platform.runLater(() -> {
            Stage stage = new Stage();
            File selected = FileChooserDialog.showDialog(stage, null); // 使用自定義的 FileChooserDialog
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