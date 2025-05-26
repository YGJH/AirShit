package AirShit;
public interface TransferCallback {
    void onStart(long totalBytes);
    void onStart(long totalBytes , String name);
    void onProgress(long bytesTransferred);
    void onComplete();
    void onComplete(String name);
    void onError(Exception e);
}
