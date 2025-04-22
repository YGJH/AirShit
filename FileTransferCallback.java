package AirShit;
public interface FileTransferCallback {
    void onProgress(int percent);
    void onComplete(boolean success);
}
