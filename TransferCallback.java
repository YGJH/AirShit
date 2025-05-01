package AirShit;
public interface TransferCallback {
    void onStart(long totalBytes);
    void onProgress(long bytesTransferred);
    void onComplete();
    void onError(Exception e);
}
