package AirShit;
import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * FileSender: Handles sending files, including potential compression.
 */
public class FileSender {
    private  String host;
    private  int port;

    
    public void println(String str) {
        // Consider using a proper logger or the LogPanel from your UI
        System.out.println(str);
    }
    
    public FileSender(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void sendFiles(String senderUserName, File selectFile , TransferCallback callback) throws IOException, InterruptedException {



    }


    // private String sanitizeRelativePath(String relativePath) { ... }
}