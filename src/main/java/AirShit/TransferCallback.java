package AirShit;
public interface TransferCallback {
    void onStart(long totalBytes);
    void onProgress(long bytesTransferred);
    void onComplete();
    void onComplete(String name);
    void onError(Exception e);
}
