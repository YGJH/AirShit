package AirShit; // 定義套件 AirShit
import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別
import javax.swing.*; // 引入 Swing 圖形界面相關類別
import java.util.random.*;

public class Main { // 定義 Main 類別
    static Random random = new Random(); // 建立隨機數生成器
    static void println(String s) {
        System.out.println(s);
    }
    private final static Client client; // 建立 Client 物件以儲存客戶端資訊

    public static Client getClient() { // 定義取得客戶端資訊的方法
        return client; // 返回客戶端資訊
    }

    public static final int DISCOVERY_PORT = 50000; // Or any other unused port

    static {
        String userName ;
        try { // 嘗試取得本機主機名稱
            userName = InetAddress.getLocalHost().getHostName(); // 取得主機名稱並指定給 USER_NAME
        } catch (UnknownHostException e) { // 異常處理：未知主機
            userName = System.getProperty("user"+UUID.randomUUID().toString().substring(0, 8)); // 使用隨機字串作為使用者名稱
        }
        client = new Client(getNonLoopbackIP(), userName , getFreeTCPPort(), DISCOVERY_PORT  , System.getProperty("os.name")); // 取得可用的 TCP 端口
    }
    
    private static Hashtable<String, Client> clientList = new Hashtable<>(); // 建立存放客戶端資訊的哈希表
    public static Hashtable<String, Client> getClientPorts() { // 定義取得客戶端端口的方法
        return clientList; // 返回客戶端哈希表
    }    
    enum SEND_STATUS { // 定義檔案傳送狀態列舉
        SEND_OK, // 傳送正常結束
        SEND_WAITING // 正在等待傳送
    }
    
    private static AtomicReference<SEND_STATUS> sendStatus = new AtomicReference<>(SEND_STATUS.SEND_OK); // 建立原子參考變數以追蹤傳送狀態


    public static String getNonLoopbackIP() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                String d = iface.getDisplayName().toLowerCase();
                String n = iface.getName().toLowerCase();
                // skip Hyper-V / virtual adapters
                if (d.contains("hyper") || n.startsWith("vethernet")) continue;
    
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    public static InetAddress getMulticastAddress() {
        try{
            return InetAddress.getByName("239.255.42.99"); // Valid multicast address
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return null;
    }
    private static NetworkInterface findCorrectNetworkInterface() {
        try {
            InetAddress local = InetAddress.getByName(client.getIPAddr());
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String d = ni.getDisplayName().toLowerCase(), 
                       n = ni.getName().toLowerCase();
                if (d.contains("hyper") || n.startsWith("vethernet")) continue;
    
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.equals(local)) return ni;
                }
            }
        } catch (Exception e) {
            System.err.println("Error finding interface: " + e.getMessage());
        }
        return null;
    }
    public static void multicastHello() {
        try {
            InetAddress group = getMulticastAddress();
            if (group == null) return;

            MulticastSocket socket = new MulticastSocket();
            socket.setTimeToLive(32); // Set TTL

            String helloMessage = client.getHelloMessage();
            byte[] sendData = helloMessage.getBytes();

            // *** CHANGE THIS: Send multicast packet to the DISCOVERY_PORT ***
            DatagramPacket packet = new DatagramPacket(
                sendData, sendData.length, group, DISCOVERY_PORT); // Use DISCOVERY_PORT
            socket.send(packet);
            socket.close();

            // System.out.println("Sent multicast hello to " + group.getHostAddress() + ":" + DISCOVERY_PORT);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void startMulticastListener() {
        new Thread(() -> {
            MulticastSocket socket = null;
            try {
                InetAddress group = getMulticastAddress();
                if (group == null) return;

                // *** CHANGE THIS: Listen on the DISCOVERY_PORT ***
                socket = new MulticastSocket(DISCOVERY_PORT);
                InetSocketAddress groupAddr = new InetSocketAddress(group, DISCOVERY_PORT); // Use DISCOVERY_PORT

                // --- Improved Interface Selection ---
                NetworkInterface netIf = findCorrectNetworkInterface();
                if (netIf == null) {
                    System.err.println("Could not find suitable network interface for multicast. Trying default.");
                    socket.joinGroup(groupAddr, null);
                } else {
                     System.out.println("Joining multicast group on interface: " + netIf.getDisplayName() + " [" + netIf.getName() + "]"); // Log name too
                     socket.joinGroup(groupAddr, netIf);
                }
                // --- End Improved Interface Selection ---


                byte[] buffer = new byte[1024];
                // *** CHANGE THIS: Update log message ***
                System.out.println("Multicast listener started on port: " + DISCOVERY_PORT);

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    // --- Process packet ---
                    String message = new String(packet.getData(), 0, packet.getLength());

                    // Ignore self-sent messages (more robust check needed if multiple local IPs)
                    InetAddress localInetAddress = InetAddress.getByName(client.getIPAddr());
                    if (packet.getAddress().equals(localInetAddress) && packet.getPort() == DISCOVERY_PORT) {
                       continue;
                    }


                    if ("PING".equals(message)) {
                        // Respond PONG directly back to sender (unicast)
                        byte[] pongData = "PONG".getBytes();
                        InetAddress senderAddress = packet.getAddress();
                        int senderPort = packet.getPort(); // PING might come from an ephemeral port, respond there
                        // Use a *different* socket for unicast replies if needed, or reuse carefully
                        try (DatagramSocket replySocket = new DatagramSocket()) {
                             DatagramPacket pongPacket = new DatagramPacket(pongData, pongData.length, senderAddress, senderPort);
                             replySocket.send(pongPacket);
                        }
                        continue;
                    }

                    Client tempClient = Client.parseMessage(message);
                    if (tempClient == null) continue;

                    // Check if client is self OR already known (use IP as key if possible)
                    if (tempClient.getIPAddr().equals(client.getIPAddr()) || clientList.containsKey(tempClient.getIPAddr())) {
                         continue;
                    }

                    // Use IP address as the key for consistency
                    clientList.put(tempClient.getIPAddr(), tempClient);
                    System.out.println("Discovered client: " + tempClient.getUserName() + " at " + tempClient.getIPAddr());

                    // Respond directly to the sender (unicast)
                    for(int i = 0; i < 3; i++) { // Send hello message 3 times
                        responseNewClient(packet.getAddress(), DISCOVERY_PORT); // Respond to the port the hello came from
                        try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }

                    // --- End Process packet ---
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                 if (socket != null && !socket.isClosed()) {
                     // Consider leaving the group if needed: socket.leaveGroup(...)
                     socket.close();
                 }
            }
        }).start();
    }


    
    public static void responseNewClient(InetAddress targetAddr, int targetPort) {
        try {
            
            System.out.println("回應新客戶端: " + targetAddr + ":" + targetPort);
            DatagramSocket socket = new DatagramSocket();
            String helloMessage = client.getHelloMessage();
            byte[] sendData = helloMessage.getBytes();
            // send the hello message 3 times
            for (int i = 0; i < 3; i++) {
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, targetAddr, targetPort);
                socket.send(sendPacket);
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            socket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }    
    public static void sendACK(Socket socket) { // 定義傳送 ACK 訊息的方法
        try { // 嘗試傳送 ACK
            OutputStream os = socket.getOutputStream(); // 取得連線的輸出串流
            os.write("ACK".getBytes()); // 傳送 ACK 訊息的位元組資料
            os.flush(); // 清空輸出串流
            System.out.println("ACK 已傳送到 " + socket.getInetAddress() + ":" + socket.getPort()); // 輸出 ACK 訊息傳送資訊
        } catch (IOException e) { // 捕捉 I/O 異常
            e.printStackTrace(); // 列印異常資訊
        }
    }
    public static void main(String[] args) { // 主方法，程式入口點
        sendStatus.set(SEND_STATUS.SEND_OK); // 設定檔案傳送初始狀態
        System.out.println("使用者名稱: " + client.getUserName() + " UDP: " + client.getUDPPort() + " TCP: " + client.getTCPPort() + " IP: " + client.getIPAddr()); // 輸出使用者名稱
        startMulticastListener();  // Start listening first
        multicastHello();          // Then announce yourself

        new Thread(() -> new Main().receiveFile()).start(); // 建立新執行緒並啟動檔案接收服務
        SwingUtilities.invokeLater(() -> { // 於事件分派執行緒中啟動 GUI
            new SendFileGUI(); // 建立並顯示檔案傳送介面
        });
        new Thread(() -> { // 建立新執行緒以檢查客戶端存活狀態
            while (true) { // 無限迴圈檢查存活狀態
                try { // 嘗試檢查存活狀態
                    Thread.sleep(5000 + (random.nextInt() % 3000)); // 每 5 秒檢查一次
                    checkAlive(); // 檢查客戶端存活狀態
                } catch (InterruptedException e) { // 捕捉中斷例外
                    e.printStackTrace(); // 列印例外資訊
                }
            }
        }).start(); // 啟動檢查存活狀態的執行緒

    }
    public static int getFreeTCPPort() { // 定義取得空閒 TCP 端口的方法
        try (ServerSocket socket = new ServerSocket(0)) { // 建立 ServerSocket 並由系統分配端口
            return socket.getLocalPort(); // 返回分配到的 TCP 端口號
        } catch (IOException e) { // 捕捉 I/O 異常
            throw new RuntimeException("No free TCP port available", e); // 拋出執行例外表示未找到可用端口
        }
    }

    public static void receiveFile() { // 定義接收檔案的方法
        try { // 嘗試建立 TCP 伺服器以接收連線
            ServerSocket serverSocket = new ServerSocket(client.getTCPPort()); // 建立伺服器並監聽 TCP_PORT
            System.out.println("TCP 伺服器啟動，等待客戶端連線 (端口 " + client.getTCPPort() + ")"); // 輸出伺服器啟動訊息
            while (true) { // 無限迴圈等待客戶端連線
                Socket socket = serverSocket.accept(); // 接受客戶端連線請求
                System.out.println("接收到來自 " + socket.getInetAddress() + " 的連線請求"); // 輸出連線來源資訊
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream())); // 建立輸入串流讀取器
                String header = reader.readLine(); // 讀取傳送的標頭資訊
                System.out.println("接收到的標頭: " + header); // 輸出接收到的標頭資訊
                System.out.println(header); // 輸出標頭資訊
                if (header == null || !header.contains(":")) { // 驗證標頭資訊格式
                    System.out.println("Invalid header. Closing connection."); // 輸出錯誤訊息
                    socket.close(); // 關閉連線
                    continue; // 繼續等待下一個連線
                }
                String[] parts = header.split(":"); // use pipe as delimiter
                
                if (parts.length != 2) {
                    System.out.println("Invalid header. Closing connection.");
                    socket.close();
                    continue;
                }
                // System.out.println("Header parts: " + header.split("|")[0] ); // 輸出標頭分割後的資訊
                String fileName = parts[0];
                long fileSize = Long.parseLong(parts[1]);
                int option = JOptionPane.showConfirmDialog(null,
                        "接受檔案傳輸?\n檔案名稱: " + fileName + "\n檔案大小: " + fileSize + " bytes",
                        "檔案傳輸",
                        JOptionPane.YES_NO_OPTION); // 顯示對話框詢問是否接受檔案
                if (option != JOptionPane.YES_OPTION) { // 如果使用者拒絕接受檔案
                    System.out.println("使用者拒絕接收檔案"); // 輸出拒絕訊息
                    socket.close(); // 關閉連線
                    continue; // 繼續等待下一個連線
                }
                JFileChooser fileChooser = new JFileChooser(); // 建立檔案選取器
                fileChooser.setSelectedFile(new File(fileName)); // 設定預設檔案名稱
                int chooserResult = fileChooser.showSaveDialog(null); // 顯示儲存檔案對話框
                if (chooserResult != JFileChooser.APPROVE_OPTION) { // 如果使用者取消選取
                    System.out.println("User cancelled save dialog."); // 輸出取消訊息
                    socket.close(); // 關閉連線
                    continue; // 繼續等待下一個連線
                }
                File saveFile = fileChooser.getSelectedFile(); // 取得使用者選擇的儲存檔案
                sendACK(socket); // 傳送 ACK 訊息以通知傳送者開始資料傳送
                FileOutputStream fos = new FileOutputStream(saveFile); // 建立檔案輸出串流以寫入接收資料
                InputStream is = socket.getInputStream(); // 取得連線的輸入串流
                byte[] buffer = new byte[10005]; // 建立資料緩衝區
                int bytesRead; // 定義讀取位元組數變數
                long totalRead = 0; // 初始化已讀取位元組總數
                while ((bytesRead = is.read(buffer)) != -1 && totalRead < fileSize) { // 持續讀取直到檔案結束或資料量達到檔案大小
                    fos.write(buffer, 0, bytesRead); // 將讀取的資料寫入檔案
                    totalRead += bytesRead; // 更新已讀取位元組數
                }
                fos.close(); // 關閉檔案輸出串流
                socket.close(); // 關閉 TCP 連線
                System.out.println("file received and saved to " + saveFile.getAbsolutePath()); // 輸出壓縮檔案儲存位置      
                System.out.println("檔案接收完成"); // 輸出檔案接收完成訊息
            }
        } catch (Exception e) { // 捕捉所有例外
            e.printStackTrace(); // 列印例外資訊
        }
    }    

    
    public static boolean sendFileToUser(String selectedUserName , File file) { // 定義傳送檔案給指定使用者的方法
        System.out.println("Looking for client with IP: " + selectedUserName);

        Client targetClient = clientList.get(selectedUserName); // 根據 IP 位址取得目標客戶端資訊

        try { // 嘗試開始檔案傳送程序
            if (sendStatus.get() == SEND_STATUS.SEND_WAITING) { // 如果已有檔案正在傳送
                System.out.println("Currently, a file is being transferred."); // 輸出當前傳送中訊息
                return false; // 返回失敗
            }
            println(selectedUserName);
            // Compress the file before sending
            File originalFile = file;
            // File compressedFile = File.createTempFile("compressed_", ".gz");
            
            Socket socket = new Socket(targetClient.getIPAddr(), targetClient.getTCPPort()); // 建立與接收者之間的 TCP 連線
            System.out.println("Starting to send file: " + originalFile.getName() + " to " + targetClient.getIPAddr() + ":" + targetClient.getTCPPort()); // 輸出開始傳送檔案訊息
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true); // 建立輸出串流以傳送檔案標頭
            out.println(originalFile.getName() + ":" + originalFile.length()); // 傳送檔案名稱與大小
            out.flush(); // 清空輸出串流
            
            short cnt = 0; // 初始化等待 ACK 的次數
            while(recevieACK(socket) == false && cnt < 3) { // 嘗試等待 ACK 回覆，最多三次
                println("Waiting for ACK..."); // 輸出等待 ACK 訊息
                Thread.sleep(300); // 延遲等待 300 毫秒
                cnt++; // 增加等待次數計數器
            }
            if (cnt == 3) { // 如果三次嘗試後仍未收到 ACK
                System.out.println("Failed to receive ACK, file sending aborted"); // 輸出失敗訊息
                socket.close(); // 關閉連線
                return false; // 返回失敗
            }
            
            FileInputStream fis = new FileInputStream(originalFile); // 建立檔案輸入串流以讀取壓縮後檔案內容
            OutputStream os = socket.getOutputStream(); // 取得 TCP 連線的輸出串流
            byte[] buffer = new byte[10005]; // 建立傳輸用緩衝區
            int bytesRead; // 定義讀取位元組數變數
            while ((bytesRead = fis.read(buffer)) != -1) { // 迴圈讀取檔案資料直到結尾
                os.write(buffer, 0, bytesRead); // 傳送讀取的資料區塊
                os.flush(); // 清空輸出串流
            }
            fis.close(); // 關閉檔案輸入串流
            socket.close(); // 關閉 TCP 連線
            System.out.println("File sent successfully"); // 輸出檔案傳送成功訊息
            
            return true; // 返回成功
        } catch (Exception e) { // 捕捉例外
            e.printStackTrace(); // 列印例外資訊
            return false; // 返回失敗
        }
    }

    public static boolean recevieACK(Socket socket) {
        try {
            InputStream is = socket.getInputStream();
            byte[] buffer = new byte[10005];
            int bytesRead = is.read(buffer);
            if (bytesRead == -1) {
                // Stream closed or nothing read, handle accordingly
                return false;
            }
            String ack = new String(buffer, 0, bytesRead);
            println("received ACK: " + ack); // 輸出接收到的 ACK 訊息
            return "ACK".equals(ack);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }


    public static void checkAlive() {
        for(String key : clientList.keySet()) { // 遍歷客戶端哈希表
            Client tempClient = clientList.get(key); // 取得客戶端資訊
            try{

                DatagramSocket socket = new DatagramSocket();
                byte[] pingData = "PING".getBytes();
                String targetAddress = tempClient.getIPAddr();  
                int targetPort = DISCOVERY_PORT; // 取得客戶端 UDP 端口號
                DatagramPacket pingPacket = new DatagramPacket(pingData, pingData.length, InetAddress.getByName(targetAddress), targetPort);
                println("ping " + targetAddress + ":" + targetPort); // 輸出 Ping 訊息
                // 發送 Ping 封包
                try {
                    socket.send(pingPacket);
                } catch (IOException e) {
                    e.printStackTrace();
                    socket.close();
                    continue;
                }
                
                // 設定超時時間
                socket.setSoTimeout(3000); // 3秒
                
                byte[] buffer = new byte[1024];
                DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(responsePacket);
                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    if ("PONG".equals(response)) {
                        // System.out.println("對方在線上！");
                    }
                } catch (SocketTimeoutException e) {
                    // 超時，表示對方可能已離線
                    clientList.remove(key); // 移除已離線的客戶端
                    // System.out.println("未收到回覆，對方可能已離線");
                }
                socket.close();
            } catch (Exception e) {
                e.printStackTrace(); // 列印例外資訊
                clientList.remove(key); // 移除已離線的客戶端
                // System.out.println("對方可能已離線，移除客戶端: " + key); // 輸出移除客戶端訊息
                continue;
            }
        }
    }


}
