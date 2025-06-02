package AirShit;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.io.IOIndexedException;

import java.util.ArrayList;
/**
 * 超高效能檔案接收器，使用 Zero Copy + Virtual Threads + 智能區塊比對
 */
public class ReceiverOptimized {
    private ServerSocketChannel serverSocket;

    private int threadCount;
    private String outputFile;
    private TransferCallback cb;

    public ReceiverOptimized(ServerSocketChannel serverSocket, int threadCount) {
        this.serverSocket = serverSocket;
        this.threadCount = threadCount;
    }

    public boolean start(
            String outputFile,
            long fileLength,
            /* port is already bound externally */
            int PORT,
            TransferCallback cb) throws InterruptedException {
        this.outputFile = outputFile;
        this.cb = cb;
        File out = new File(this.outputFile);
        if( out.exists() ) {
            out.delete(); // 如果檔案已存在，則刪除它
            try {
                out.getParentFile().mkdirs(); // 確保目錄存在
                out.createNewFile(); // 確保檔案存在
            } catch (IOException e) {
                return false;
            }
        } else {
            try {
                out.getParentFile().mkdirs(); // 確保目錄存在
                out.createNewFile(); // 確保檔案存在
            } catch (IOException e) {
                return false;
            }
        }
        if(fileLength <= 500L * 1024L * 1024L ) { 
            threadCount = 1; // 如果檔案小於 500MB，則只使用單執行緒
        }
        // 用虛擬執行緒接收每個 chunk，直到總 byte 數達到 fileLength
        java.util.concurrent.atomic.AtomicLong bytesReceived = new java.util.concurrent.atomic.AtomicLong(0);
        ExecutorService chunkExecutor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            System.out.println("ReceiverOptimized: expecting fileLength=" + fileLength + ", threads=" + threadCount);
             serverSocket.configureBlocking(true);
             while (bytesReceived.get() < fileLength) {
                 System.out.println("ReceiverOptimized: waiting for chunk connection...");
                 SocketChannel clientChannel = serverSocket.accept();
                 if (clientChannel != null) {
                     chunkExecutor.submit(() -> {
                         try {
                            System.out.println("ReceiverOptimized: chunk connection accepted");
                             // 1. header
                             ByteBuffer metaBuf = ByteBuffer.allocate(Long.BYTES * 2);
                             while (metaBuf.hasRemaining()) clientChannel.read(metaBuf);
                             metaBuf.flip();
                             long offset = metaBuf.getLong();
                             long length = metaBuf.getLong();
                             System.out.println("ReceiverOptimized: header offset=" + offset + ", length=" + length);
                             // 2. ACK
                             ByteBuffer ackBuf = ByteBuffer.allocate(Long.BYTES * 2);
                             ackBuf.putLong(offset).putLong(length).flip();
                             clientChannel.write(ackBuf);
                           