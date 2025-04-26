package AirShit;
public interface TransferCallback {
    void onStart(String fileName, long totalBytes);
    void onProgress(String fileName, long bytesTransferred);
    void onComplete(String fileName);
    void onError(String fileName, Exception e);
}
