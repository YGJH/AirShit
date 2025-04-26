package AirShit;
public interface TransferCallback {
    void onStart(long totalBytes);
    void onProgress(long bytesTransferred);
    void onError(Exception e);
}
