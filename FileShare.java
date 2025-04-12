package AirShit;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

public class FileShare {
    // UDP 與 TCP 預設端口號
    private static int TCP_PORT; 
    private static int UDP_PORT;
    static {
        TCP_PORT = getFreeTCPPort();
        UDP_PORT = getFreeUDPPort();
    }
    // 訊息定義
    private static       String IPAddr;

    private static final String OS = System.getProperty("os.name");
    private static       Random random = new Random();
    // 使用者名稱
    private static       String USER_NAME ;
    static{
        try {
            // Try to get the host name from the local IP
            USER_NAME = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // Fall back to a system property (this is usually the OS user name)
            USER_NAME = System.getProperty("user"+UUID.randomUUID().toString().substring(0, 8));
        }
    }
    // Use getHelloMessage() method to get current hello message.


    // client 端口號
    private static Hashtable<String, Client> clientPorts = new Hashtable<>();
    public static Hashtable<String, Client> getClientPorts() {
        return clientPorts;
    }
    
    // 傳送狀態
    enum SEND_STATUS {
        SEND_OK,
        SEND_WAITING
    }
    
    // 傳送檔案的執行緒


    private static AtomicReference<SEND_STATUS> sendStatus = new AtomicReference<>(SEND_STATUS.SEND_OK);

    // 取得本地IP
    public static String getNonLoopbackIP() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }
    private static InetAddress getBroadcastAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isLoopback() && ni.isUp()) {
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        InetAddress broadcast = ia.getBroadcast();
                        if (broadcast != null) {
                            return broadcast;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void broadCastHello() {
        String helloMessage = getHelloMessage();
        byte[] sendData = helloMessage.getBytes();
        InetAddress broadcast = getBroadcastAddress();
        if(broadcast == null) {
            System.out.println("No broadcast address found, aborting broadcast.");
            return;
        }
        
        // Loop through a range of UDP ports (or if you prefer, send to a fixed port)
        for (int port = 3000; port < 65530; port++) {
            // Skip the port already used by the FileShare UDP server
            if (port == UDP_PORT) continue;
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                // Send the packet to the proper broadcast address
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, broadcast, port);
                socket.send(sendPacket);
                socket.close();
            } catch (Exception e) {
                // Log or ignore per your needs
            }
        }
    }
    public static void responseNewClient(Client client) {
        try {
            // Create a DatagramSocket to send the response
            DatagramSocket udpSocket = new DatagramSocket();
            udpSocket.setBroadcast(true);
            // Prepare the response message using the hello message
            byte[] sendData = getHelloMessage().getBytes();
            // Use the client's IP and UDP server port for the response
            InetAddress clientAddress = InetAddress.getByName(client.getIPAddr());
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, client.getUDPPort());
            System.out.println("Responded to client at " + client.getIPAddr() + ":" + client.getUDPPort());
            udpSocket.send(sendPacket);
            udpSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public static void sendACK(Socket socket) {
        try {
            OutputStream os = socket.getOutputStream();
            os.write("ACK".getBytes());
            os.flush();
            System.out.println("ACK 已傳送到 " + socket.getInetAddress() + ":" + socket.getPort());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public static void main(String[] args) {
        // Start core services as before...
        IPAddr = getNonLoopbackIP();
        sendStatus.set(SEND_STATUS.SEND_OK);
        System.out.println("IP 地址: " + IPAddr + " TCP端口號: " + TCP_PORT + " UDP端口號: " + UDP_PORT + " 使用者名稱: " + USER_NAME + " 作業系統: " + OS);
        new Thread(() -> new FileShare().UDPServer()).start();
        broadCastHello();
        new Thread(() -> new FileShare().receiveFile()).start();
        
        // Launch the SendFile GUI on the EDT
        SwingUtilities.invokeLater(() -> {
            new SendFileGUI();
        });
    }

    public static int getFreeTCPPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No free TCP port available", e);
        }
    }
    public static int getFreeUDPPort() {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("No free UDP port available", e);
        }
    }


    // 接收檔案
    public static void receiveFile() {
        try {
            ServerSocket serverSocket = new ServerSocket(TCP_PORT);
            System.out.println("TCP 伺服器啟動，等待客戶端連線 (端口 " + TCP_PORT + ")");
            while (true) {
                // 接收客戶端連線請求
                Socket socket = serverSocket.accept();
                System.out.println("接收到來自 " + socket.getInetAddress() + " 的連線請求");
    
                // Read header from sender: expected format "fileName:fileSize"
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String header = reader.readLine();
                if (header == null || !header.contains(":")) {
                    System.out.println("Invalid header. Closing connection.");
                    socket.close();
                    continue;
                }
                String[] parts = header.split(":");
                String fileName = parts[0];
                long fileSize = Long.parseLong(parts[1]);
                
                // Ask user if they want to accept the file (on the EDT)
                int option = JOptionPane.showConfirmDialog(null,
                        "接受檔案傳輸?\n檔案名稱: " + fileName + "\n檔案大小: " + fileSize + " bytes",
                        "檔案傳輸",
                        JOptionPane.YES_NO_OPTION);
                if (option != JOptionPane.YES_OPTION) {
                    System.out.println("使用者拒絕接收檔案");
                    socket.close();
                    continue;
                }
                
                // Let user choose save location
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setSelectedFile(new File(fileName));
                int chooserResult = fileChooser.showSaveDialog(null);
                if (chooserResult != JFileChooser.APPROVE_OPTION) {
                    System.out.println("User cancelled save dialog.");
                    socket.close();
                    continue;
                }
                File saveFile = fileChooser.getSelectedFile();
                
                // Send ACK so sender begins data transfer
                sendACK(socket);
                
                // Receive the file data (reading any remaining bytes after header)
                FileOutputStream fos = new FileOutputStream(saveFile);
                InputStream is = socket.getInputStream();
                byte[] buffer = new byte[10005];
                int bytesRead;
                long totalRead = 0;
                while ((bytesRead = is.read(buffer)) != -1 && totalRead < fileSize) {
                    fos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;
                }
                fos.close();
                socket.close();
                System.out.println("檔案已成功接收並儲存到 " + saveFile.getAbsolutePath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }    
    public static void UDPServer() {
        try {
            // Bind to UDP_PORT so replies sent to that port can be received.
            DatagramSocket udpSocket = new DatagramSocket(UDP_PORT);
            byte[] recvBuf = new byte[100];
            System.out.println("UDP 服務開0始，監聽端口 " + UDP_PORT);
            while (true) {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                udpSocket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("收到 UDP 訊息: " + message + "，來自 " + packet.getAddress());
                // Process the received message and reply using responseNewClient()
                Client client = new Client();
                if (Client.parseMessage(message, client)) {
                    if(client.getIPAddr().equals(IPAddr)
                            && (client.getPort() == TCP_PORT || client.getPort() == UDP_PORT) || clientPorts.containsKey(client.getUserName())) {
                        continue;
                    }
                    System.out.println("客戶端名稱: " + client.getUserName());
                    clientPorts.put(client.getUserName(), client);
                    Thread.sleep(20); // Wait 2 seconds
                    responseNewClient(client);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    // 傳送檔案
    // Send file to a specific user (by user name) using the information stored in clientPorts
    public static boolean sendFileToUser(String selectedUser, File file) {
        Client client = clientPorts.get(selectedUser);
        if (client == null) {
            System.out.println("Client not found: " + selectedUser);
            return false;
        }
        
        try {
            // Check if already transferring
            if(sendStatus.get() == SEND_STATUS.SEND_WAITING) {
                System.out.println("Currently, a file is being transferred.");
                return false;
            }
            // Connect to the receiver
            Socket socket = new Socket(client.getIPAddr(), client.getPort());
            System.out.println("Starting to send file: " + file.getName() + " to " + client.getIPAddr() + ":" + client.getPort());
            
            // First send file name and file size
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            out.println(file.getName() + ":" + file.length());
            out.flush();
            
            // Wait for ACK (simplified: try a few times)
            short cnt = 0;
            while(recevieACK(socket) == false && cnt < 3) {
                Thread.sleep(300);
                cnt++;
            }
            if (cnt == 3) {
                System.out.println("Failed to receive ACK, file sending aborted");
                socket.close();
                return false;
            }
            
            // Send file data
            FileInputStream fis = new FileInputStream(file);
            OutputStream os = socket.getOutputStream();
            byte[] buffer = new byte[10005];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                os.flush();
            }
            fis.close();
            socket.close();
            System.out.println("File sent successfully");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public void printHashTable(Hashtable<String, Client> clientPorts) {
        System.out.println("目前有 " + clientPorts.size() + " 個已連線的客戶端: ");
        for (String key : clientPorts.keySet()) {
            System.out.println("使用者名稱: " + key + ", 端口號: " + clientPorts.get(key).getPort() + ", 作業系統: " + clientPorts.get(key).getOS());
        }
    }
    public static boolean recevieACK(Socket socket) {
        try {
            InputStream is = socket.getInputStream();
            byte[] buffer = new byte[10005];
            int bytesRead = is.read(buffer);
            String ack = new String(buffer, 0, bytesRead);
            if (ack.equals("ACK")) {
                return true;
            } else {
                return false;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static int getPort() {
        try {
            ServerSocket serverSocket = new ServerSocket(0); // 0 = 系統自動選 port
            int assignedPort = serverSocket.getLocalPort();  // 獲取實際 port
            return assignedPort;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String getHelloMessage() {
        return IPAddr + ":" + USER_NAME + ":" + TCP_PORT + ":" + UDP_PORT + ":" + OS;
    }

}
