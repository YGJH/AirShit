package AirShit;
public interface TransferCallback {
    void onStart(long totalBytes);
    void onStart(long totalBytes , String name);
    void onProgress(long bytesTransferred);
    void onComplete();
    void onComplete(String name);
    void onError(Exception e);
    
    // Default methods for file count tracking with backward compatibility
    default void onFileStart(int currentFile, int totalFiles, String fileName) {
        // Default implementation does nothing for backward compatibility
    }
    
    default void onFileComplete(int currentFile, int totalFiles, String fileName) {
        // Default implementation does nothing for backward compatibility
    }
}
