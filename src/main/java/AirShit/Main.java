package AirShit; // 定義套件 AirShit

import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.nio.charset.StandardCharsets;
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicLong; // 引入原子長整數類別
import javax.swing.*; // 引入 Swing 圖形界面相關類別
import java.awt.Font; // 引入 AWT Font類別
import AirShit.ui.LogPanel;
// import net.kuujo.vertigo.io.FileReceiver;

public class Main { // 定義 Main 類別
    static Random random = new Random(); // 建立隨機數生成器
    public static SendFileGUI GUI;
    private static Thread listenThread; // 建立監聽執行緒
    private static MulticastSocket listenerSocket; // persistent socket for multicast listener
    static FileReceiver fileReceiver;

    static void println(String s) {
        System.out.println(s);
    }

    private static Client client; // 建立 Client 物件以儲存客戶端資訊

    public static Client getClient() { // 定義取得客戶端資訊的方法
        return client; // 返回客戶端資訊
    }

    public static void clearClientList() { // 定義清除客戶端列表的方法
        clientList.clear(); // 清空客戶端哈希表
    }

    public static int DISCOVERY_PORT = 23333;
    public static String MULTICAST_GROUP = "all-routers.mcast.net";

    static {
        String userName;
        try { // 嘗試取得本機主機名稱
            userName = InetAddress.getLocalHost().getHostName(); // 取得主機名稱並指定給 USER_NAME
        } catch (UnknownHostException e) { // 異常處理：未知主機
            userName = System.getProperty("user" + UUID.randomUUID().toString().substring(0, 8)); // 使用隨機字串作為使用者名稱
        }
        int tcp1 = getFreeTCPPort();
        int tcp2 = getFreeTCPPort();
        while (tcp1 == tcp2) { // 確保兩個 TCP 端口不相同
            tcp2 = getFreeTCPPort(); // 重新取得第二個 TCP 端口
        }

        client = new Client(getNonLoopbackIP(), userName, tcp1, tcp2, DISCOVERY_PORT,
                System.getProperty("os.name")); // 取得可用的 TCP 端口
    }

    private static Hashtable<String, Client> clientList = new Hashtable<>(); // 建立存放客戶端資訊的哈希表

    public static Hashtable<String, Client> getClientList() { // 定義取得客戶端端口的方法
        return clientList; // 返回客戶端哈希表
    }

    enum SEND_STATUS { // 定義檔案傳送狀態列舉
        SEND_OK, // 傳送正常結束
        SEND_WAITING // 正在等待傳送
    }

    public static AtomicReference<SEND_STATUS> sendStatus = new AtomicReference<>(SEND_STATUS.SEND_OK); // 建立原子參考變數以追蹤傳送狀態

    public static String getNonLoopbackIP() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    System.out.println(
                            "getNonLoopbackIP: Skipping interface '" + ni.getDisplayName() + "': Not up or loopback.");
                    continue;
                }

                String name = ni.getDisplayName().toLowerCase();
                if (name.contains("hyper-v") || name.contains("filter") || name.contains("vmware") ||
                        name.contains("vbox") || name.contains("virtualbox")) {
                    System.out.println("getNonLoopbackIP: Skipping interface '" + ni.getDisplayName()
                            + "': Virtualization software interface.");
                    continue;
                }

                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("getNonLoopbackIP: Exception while finding IP: " + e.getMessage());
            e.printStackTrace();
        }
        // fallback
        return "127.0.0.1";
    }

    public static InetAddress getMulticastAddress() {
        try {
            return InetAddress.getByName(MULTICAST_GROUP);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            try {
                MULTICAST_GROUP = "224.0.0.2";
                return InetAddress.getByName(MULTICAST_GROUP);
            } catch (UnknownHostException e1) {
                e1.printStackTrace();
            }
        }
        return null;
    }

    // 新增 setter
    public static void setDiscoveryPort(int port) {
        DISCOVERY_PORT = port;
    }

    public static void setMulticastGroup(String group) {
        MULTICAST_GROUP = group;
    }

    private static NetworkInterface findCorrectNetworkInterface() {
        // 如果用戶已選擇特定網卡，直接使用
        if (!useAutoDetection && selectedNetworkInterface != null) {
            try {
                if (selectedNetworkInterface.isUp() && selectedNetworkInterface.supportsMulticast()) {
                    System.out.println("findCorrectNetworkInterface: Using user-selected interface: '" +
                            selectedNetworkInterface.getDisplayName() + "'");
                    return selectedNetworkInterface;
                } else {
                    System.err.println("findCorrectNetworkInterface: User-selected interface '" +
                            selectedNetworkInterface.getDisplayName()
                            + "' is not available. Falling back to auto-detection.");
                    useAutoDetection = true;
                    selectedNetworkInterface = null;
                }
            } catch (SocketException e) {
                System.err.println(
                        "findCorrectNetworkInterface: Error checking user-selected interface: " + e.getMessage());
                useAutoDetection = true;
                selectedNetworkInterface = null;
            }
        }
        // 原有的自動檢測邏輯
        // System.out.println("findCorrectNetworkInterface: Searching for suitable
        // interface for multicast...");
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                // System.out.println("findCorrectNetworkInterface: Considering interface: '" +
                // ni.getDisplayName() + "' (Name: " + ni.getName() +
                // ", Up: " + ni.isUp() + ", Loopback: " + ni.isLoopback() +
                // ", Virtual: " + ni.isVirtual() + ", Supports Multicast: " + (ni.isUp() ?
                // ni.supportsMulticast() : "N/A (not up)") + ")");

                if (!ni.isUp() || ni.isLoopback()) {
                    System.out.println("findCorrectNetworkInterface: Skipping interface '" + ni.getDisplayName()
                            + "': Not up or loopback.");
                    continue;
                }
                if (!ni.supportsMulticast()) {
                    System.out.println("findCorrectNetworkInterface: Skipping interface '" + ni.getDisplayName()
                            + "': Does not support multicast.");
                    continue;
                }

                // 跳過特定的虛擬網卡，但保留 VPN 介面 (如 OpenVPN, WireGuard)

                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        // System.out.println("findCorrectNetworkInterface: Selected interface: '" +
                        // ni.getDisplayName() + "' with IPv4 address: " + addr.getHostAddress());
                        return ni;
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println(
                    "findCorrectNetworkInterface: SocketException while finding network interface: " + e.getMessage());
            e.printStackTrace();
        }
        // System.err.println("findCorrectNetworkInterface: No suitable network
        // interface found after checking all interfaces.");
        return null;
    }

    public static void multicastHello() {
        try (
                MulticastSocket socket = new MulticastSocket();) { // Sender socket binds to any free port
            InetAddress group = getMulticastAddress();
            if (group == null) {
                System.err.println("Sender: Multicast group address is null. Cannot send HELLO.");
                return;
            }

            byte[] sendData = client.getHelloMessage().getBytes("UTF-8");
            socket.setTimeToLive(32); // TTL for multicast packets
            socket.setReuseAddress(true); // Good practice for multicast sending sockets, before any bind/send.

            NetworkInterface nif = findCorrectNetworkInterface();

            if (nif != null) {
                try {
                    socket.setNetworkInterface(nif);
                } catch (SocketException e) {
                    System.err.println("Sender: FAILED to set network interface to '" + nif.getDisplayName() + "': "
                            + e.getMessage() + ". OS will choose.");
                }
                // Joining the group on the sender can be important for some OSes to correctly
                // source the packet
                try {
                    socket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), nif);
                } catch (IOException e) {
                    System.err.println("Sender: WARN - Failed to join multicast group on specific interface '" +
                            nif.getDisplayName() + "': " + e.getMessage() + ". Sending might still work.");
                }
            } else {
                try {
                    socket.joinGroup(group);
                    System.out.println("Sender: Joined multicast group on default interfaces as fallback.");
                } catch (IOException e) {
                    System.err.println(
                            "Sender: WARN - Failed to join multicast group on default interfaces: " + e.getMessage());
                }
            }

            DatagramPacket packet = new DatagramPacket(
                    sendData, sendData.length, group, DISCOVERY_PORT);
            socket.send(packet);

            // socket.close() will handle leaving the group.
        } catch (Exception e) {
            System.err.println("Sender: Exception in multicastHello: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void startMulticastListener() {
        try {
            // close previous socket if open
            if (listenerSocket != null && !listenerSocket.isClosed()) {
                listenerSocket.close();
            }
            listenerSocket = new MulticastSocket(DISCOVERY_PORT);
             // MulticastSocket(int port) constructor calls setReuseAddress(true) internally.

             InetAddress group = getMulticastAddress();
             if (group == null) {
                 return;
             }

             listenerSocket.setTimeToLive(32); // For any replies, though listener primarily receives.
             System.out.println("using network interface: " + findCorrectNetworkInterface());
             NetworkInterface iface = findCorrectNetworkInterface();
             boolean joinedGroup = false;
             if (iface != null) {
                 try {
                    listenerSocket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), iface);
                     joinedGroup = true;
                 } catch (IOException e) {
                     // fallback below
                 }
             } else {
             }

             if (!joinedGroup) {
                listenerSocket.joinGroup(group);
                 joinedGroup = true;
             }

             byte[] buffer = new byte[1024];
            while (true) { // Main listening loop
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                listenerSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                System.out.println("Received multicast (" + packet.getAddress().getHostAddress() + ":"
                        + packet.getPort() + "): " + message);

                boolean listChanged = true;

                if (message.startsWith(("HEARTBEAT" + Client.SPLIT_CHAR))) {
                   String[] parts = message.split(Pattern.quote(Client.SPLIT_CHAR));
                    if (parts.length >= 5) { // IP, Name, TCPPort, UDPPort(Discovery), OS
                        String clientIp = parts[1];
                        String clientName = parts[2];
                        // Check if client is self
                        if (clientIp.equals(client.getIPAddr()) && clientName.equals(client.getUserName())) {
                            // It's our own heartbeat
                        } else if (!clientList.containsKey(clientName)) {
                            Client tempClient = new Client(clientIp, clientName, Integer.parseInt(parts[3]),
                                    Integer.parseInt(parts[4]),
                                    Integer.parseInt(parts[5]), parts[6]); // Assuming parts[5] is discovery port,
                                                                           // parts[6] is OS
                            clientList.put(tempClient.getUserName(), tempClient);
                            System.out
                                    .println("Main: Added new client from HEARTBEAT: " + tempClient.getUserName());
                            listChanged = true;
                        }
                    }
                    // Respond to HEARTBEAT
                    byte[] resp = "ALIVE".getBytes(StandardCharsets.UTF_8);
                    DatagramPacket reply = new DatagramPacket(resp, resp.length, packet.getAddress(),
                            packet.getPort());
                    listenerSocket.send(reply);

                } else if (Client.isHelloMessage(message)) { // Assuming Client.isHelloMessage checks format
                    Client tempClient = Client.parseMessage(message);
                    if(message.split(Pattern.quote(Client.SPLIT_CHAR)).length > 6) { // no reply
                        clientList.put(tempClient.getUserName(), tempClient);
                        listChanged = true;
                    }
                    else if (tempClient != null) {
                        if (tempClient.getIPAddr().equals(client.getIPAddr())
                                && tempClient.getUserName().equals(client.getUserName())) {
                            // It's our own HELLO message
                        } else if (!clientList.containsKey(tempClient.getUserName())) {
                            clientList.put(tempClient.getUserName(), tempClient);
                            listChanged = true;
                            // Respond directly to the sender (unicast)
                            Thread.sleep(100); // Slight delay to avoid flooding
                            responseNewClient(InetAddress.getByName(tempClient.getIPAddr()), tempClient.getUDPPort() , false);
                        } else {
                            responseNewClient(InetAddress.getByName(tempClient.getIPAddr()), tempClient.getUDPPort() , true);
                        }
                    }
                }
                if (listChanged) {
                    if (GUI != null && SendFileGUI.INSTANCE != null
                            && SendFileGUI.INSTANCE.getClientPanel() != null) {
                        // System.out.println("Main: Client list changed, requesting GUI refresh.");
                        SwingUtilities
                                .invokeLater(() -> SendFileGUI.INSTANCE.getClientPanel().refreshGuiListOnly());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // listenerSocket stays open until closed on restart or interface change
    }

    public static void responseNewClient(InetAddress targetAddr, int targetPort , boolean NoReply) {
        try (
                DatagramSocket socket = new DatagramSocket();) {
            String helloMessage = client.getHelloMessage();
            if (NoReply) {
                helloMessage = helloMessage + Client.SPLIT_CHAR + "NOREPLY"; // Reply to existing client
            }
            byte[] sendData = helloMessage.getBytes("UTF-8");
            // send the hello message 3 times
            for (int i = 0; i < 3; i++) {
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, targetAddr, targetPort);
                socket.send(sendPacket);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendACK(Socket socket) { // 定義傳送 ACK 訊息的方法
        try { // 嘗試傳送 ACK
            OutputStream os = socket.getOutputStream(); // 取得連線的輸出串流
            os.write("ACK".getBytes("UTF-8")); // 傳送 ACK 訊息的位元組資料
            os.flush(); // 清空輸出串流
        } catch (IOException e) { // 捕捉 I/O 異常
            e.printStackTrace(); // 列印異常資訊
        }
    }

    private static ServerSocket lockSocket; // 用於鎖定應用程式實例
    // stored callback for file receiver
    private static TransferCallback fileReceiverCallback;

    public static void main(String[] args) { // 主方法，程式入口點
        System.setProperty("file.encoding", "UTF-8");
        SwingUtilities.invokeLater(() -> {
            GUI = new SendFileGUI();
        });

        // 2) Install a Unicode‐capable default font (e.g. Segoe UI Emoji, Microsoft
        // YaHei, or Noto)
        Font uiFont = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
        UIDefaults d = UIManager.getLookAndFeelDefaults();
        for (Object key : d.keySet()) {
            if (key.toString().toLowerCase().endsWith(".font")) {
                UIManager.put(key, uiFont);
            }
        }

        sendStatus.set(SEND_STATUS.SEND_OK); // 設定檔案傳送初始狀態
        System.out.println("使用者名稱: " + client.getUserName() + " UDP: " + client.getUDPPort() + " TCP: "
                + client.getIPAddr()); // 輸出使用者名稱

        listenThread = new Thread(() -> {startMulticastListener();}); // Start listening first
        listenThread.start(); // 啟動監聽執行緒
        fileReceiver = new FileReceiver(client.getTCPPort(), client.getTCPPort2()); // Temporarily commented out

        TransferCallback cb = new TransferCallback() {
            AtomicLong totalReceived = new AtomicLong(0);
            long totalBar = 0;
            long lasPct = -1;

            @Override
            public void onStart(long totalBytes) {
                totalBar = totalBytes;
                totalReceived.set(0);
                GUI.log("Receiving " + SendFileGUI.formatFileSize(totalBytes));
                sendStatus.set(SEND_STATUS.SEND_WAITING);
                SwingUtilities.invokeLater(() -> {
                    GUI.sendPanel.getSendButton().setEnabled(false);
                    GUI.recvPanel.getLabel().setVisible(true);
                    GUI.recvPanel.getProgressBar().setVisible(true);
                    GUI.recvPanel.getProgressBar().setMaximum(100);
                    GUI.recvPanel.getProgressBar().setValue(0);
                });
            }

            public void onStart(long totalBytes, String name) {
                totalBar = totalBytes;
                totalReceived.set(0);
                GUI.log("Receiving " + SendFileGUI.formatFileSize(totalBytes));
                sendStatus.set(SEND_STATUS.SEND_WAITING);
                SwingUtilities.invokeLater(() -> {
                    GUI.recvPanel.getLabel().setText("Receiving " + name);
                    GUI.sendPanel.getSendButton().setEnabled(false);
                    GUI.recvPanel.getLabel().setVisible(true);
                    GUI.recvPanel.getProgressBar().setVisible(true);
                    GUI.recvPanel.getProgressBar().setMaximum(100);
                    GUI.recvPanel.getProgressBar().setValue(0);
                });
            }

            @Override
            public void onProgress(long bytesTransferred) {
                long cumul = totalReceived.addAndGet(bytesTransferred);
                SwingUtilities.invokeLater(() -> {
                    int pct = (int) (cumul * 100 / totalBar);
                    GUI.recvPanel.getProgressBar().setValue((int) pct);
                    if (pct % 10 == 0 && pct != lasPct) {
                        lasPct = pct;
                        GUI.log("Progress: " + pct + "% (" + SendFileGUI.formatFileSize(cumul) + ")");
                    }
                });
            }

            @Override
            public void onComplete() {
                sendStatus.set(SEND_STATUS.SEND_OK);

                SwingUtilities.invokeLater(() -> {
                    GUI.recvPanel.getProgressBar().setValue(100);
                    GUI.sendPanel.getSendButton().setEnabled(true);
                    GUI.log("Transfer complete");
                    GUI.recvPanel.getProgressBar().setVisible(false);
                    // GUI.recvPanel.getLabel().setVisible(false);
                    GUI.recvPanel.getFileCountLabel().setText(""); // Clear file count
                });
            }

            public void onComplete(String name) {
                sendStatus.set(SEND_STATUS.SEND_OK);

                SwingUtilities.invokeLater(() -> {
                    GUI.log(name + " is transfer complete");
                    GUI.recvPanel.getProgressBar().setValue(100);
                    GUI.sendPanel.getSendButton().setEnabled(true);
                    GUI.log("Transfer complete");
                    GUI.recvPanel.getProgressBar().setVisible(false);
                    GUI.recvPanel.getLabel().setVisible(false);
                    GUI.recvPanel.getFileCountLabel().setText(""); // Clear file count
                });
            }

            @Override
            public void onError(Exception e) {
                sendStatus.set(SEND_STATUS.SEND_OK);
                SwingUtilities.invokeLater(() -> {
                    GUI.log("Error: " + e.getMessage());
                    GUI.sendPanel.getSendButton().setEnabled(true);
                    GUI.recvPanel.getProgressBar().setVisible(false);
                    GUI.recvPanel.getProgressBar().setValue(0);
                    GUI.recvPanel.getLabel().setVisible(false);
                    GUI.recvPanel.getFileCountLabel().setText(""); // Clear file count
                });
            }

            @Override
            public void onFileStart(int currentFile, int totalFiles, String fileName) {
                SwingUtilities.invokeLater(() -> {
                    String fileCountText = "File " + currentFile + " of " + totalFiles;
                    GUI.recvPanel.getFileCountLabel().setText(fileCountText);
                    GUI.log("Starting file " + currentFile + "/" + totalFiles + ": " + fileName);
                });
            }

            @Override
            public void onFileComplete(int currentFile, int totalFiles, String fileName) {
                SwingUtilities.invokeLater(() -> {
                    GUI.log("Completed file " + currentFile + "/" + totalFiles + ": " + fileName);
                });
            }
        };
        fileReceiverCallback = cb;
        new Thread(() -> {
            try {
                fileReceiver.start(fileReceiverCallback);
            } catch (IOException e) {
                e.printStackTrace();
                GUI.log("FileReceiver failed to start: " + e.getMessage());
            }
        }, "file-receiver-thread").start();

        try {
            Thread.sleep(500); // 等待 100 毫秒以確保 GUI 已經啟動
            multicastHello(); // Then announce yourself
            Thread.sleep(100); // 等待 100 毫秒以確保 GUI 已經啟動
            multicastHello(); // Then announce yourself
            Thread.sleep(100); // 等待 100 毫秒以確保 GUI 已經啟動
        } catch (Exception e) {
        }

        new Thread(() -> { // 建立新執行緒以檢查客戶端存活状态
            while (true) { // 無限迴圈檢查存活狀態
                try { // 嘗試檢查存活狀態
                    Thread.sleep(5000); // 每 50 millisecond 秒檢查一次
                    checkAlive(); // 檢查客戶端存活狀態
                } catch (InterruptedException e) { // 捕捉中斷例外
                    e.printStackTrace(); // 列印例外資訊
                }
            }
        }).start(); // 啟動檢查存活狀態的執行緒
    }

    public static int getFreeTCPPort() { // 定義取得空閒 TCP 端口的方法
        while (true) {
            try (ServerSocket socket = new ServerSocket(0)) { // 建立 ServerSocket 並由系統分配端口
                return socket.getLocalPort(); // 返回分配到的 TCP 端口號
            } catch (IOException e) { // 捕捉 I/O 異常
                continue;
            }
        }
    }

    public static void checkAlive() {
        byte[] ping = ("HEARTBEAT" + Client.SPLIT_CHAR + client.getHelloMessage()).getBytes(StandardCharsets.UTF_8);
        ArrayList<String> dead = new ArrayList<>();
        // Create a temporary copy of keys to iterate over, to avoid
        // ConcurrentModificationException
        ArrayList<String> currentClientKeys = new ArrayList<>(clientList.keySet());

        for (String name : currentClientKeys) {
            Client c = clientList.get(name);
            if (c == null)
                continue; // Should not happen if keySet is from clientList

            // Do not ping self
            if (c.getIPAddr().equals(client.getIPAddr()) && c.getUserName().equals(client.getUserName())) {
                continue;
            }

            boolean alive = false;
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setSoTimeout(1000); // Reduced timeout for faster check
                InetAddress addr = InetAddress.getByName(c.getIPAddr());
                ds.send(new DatagramPacket(ping, ping.length, addr, c.getUDPPort())); // Ping client's discovery port
                byte[] buf = new byte[64];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                ds.receive(resp);
                String reply = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                if ("ALIVE".equals(reply))
                    alive = true;
            } catch (IOException ignore) {
                // timeout or error => not alive
                System.out.println("Client " + name + " did not respond to heartbeat. Assuming dead.");
            }
            if (!alive) {
                dead.add(name);
            }
        }

        boolean listChangedInCheckAlive = false;
        for (String name : dead) {
            if (clientList.remove(name) != null) { // Check if removal actually happened
                println("Main: Removed dead client: " + name);
                listChangedInCheckAlive = true;
            }
        }

        if (listChangedInCheckAlive) {
            if (GUI != null && SendFileGUI.INSTANCE != null && SendFileGUI.INSTANCE.getClientPanel() != null) {
                System.out.println("Main (checkAlive): Client list changed, requesting GUI refresh.");
                SwingUtilities.invokeLater(() -> SendFileGUI.INSTANCE.getClientPanel().refreshGuiListOnly());
            }
        }
    }

    /**
     * Restart the application by shutting down all threads except the main thread.
     */
    public static void restart() {
        // Dispose and recreate GUI without resetting user settings
        SwingUtilities.invokeLater(() -> {
            if (GUI != null) {
                GUI.dispose();
            }
            GUI = new SendFileGUI();
        });
        // Clear client list
        clientList.clear();
        // Restart multicast listener
        // ensure old socket closed by startMulticastListener
        listenThread = new Thread(() -> startMulticastListener(), "multicast-listener-thread");
        listenThread.start();
         // Restart file receiver thread
         fileReceiver = new FileReceiver(client.getTCPPort(), client.getTCPPort2());
         new Thread(() -> {
             try {
                 fileReceiver.start(fileReceiverCallback);
             } catch (IOException e) {
                 e.printStackTrace();
                 GUI.log("FileReceiver restart failed: " + e.getMessage());
             }
         }, "file-receiver-thread").start();
    }

    public static void manuallyAddClient(String ip, int port) {
        try (
                DatagramSocket socket = new DatagramSocket();) {
            String helloMessage = client.getHelloMessage();
            byte[] sendData = helloMessage.getBytes("UTF-8");
            // send the hello message 3 times
            for (int i = 0; i < 3; i++) {
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName(ip),
                        port);
                socket.send(sendPacket);
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    // network-interface selection state (used in findCorrectNetworkInterface and binding)
    private static NetworkInterface selectedNetworkInterface = null;
    private static boolean useAutoDetection = true;
    
    public static List<NetworkInterface> getAvailableNetworkInterfaces() throws SocketException {
        List<NetworkInterface> result = new ArrayList<>();
        Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
        for (NetworkInterface ni : Collections.list(nets)) {
            if (ni.isUp() && !ni.isLoopback()) {
                result.add(ni);
            }
        }
        return result;
    }

    public static void setSelectedNetworkInterface(NetworkInterface networkInterface) {
        selectedNetworkInterface = networkInterface;
        useAutoDetection = (networkInterface == null);

        // 更新客戶端的 IP 地址
        if (networkInterface != null) {
            String newIP = getIPFromNetworkInterface(networkInterface);
            if (newIP != null && !newIP.equals(client.getIPAddr())) {
                // 創建新的客戶端實例
                client = new Client(newIP, client.getUserName(), client.getTCPPort(), client.getTCPPort2(),
                        client.getUDPPort(), client.getOS());
                // LogPanel.log("Network interface changed to: " +
                // networkInterface.getDisplayName() + " (IP: " + newIP + ")");
            }
        } else {
            // 使用自動檢測
            String newIP = getNonLoopbackIP();
            if (!newIP.equals(client.getIPAddr())) {
                client = new Client(newIP, client.getUserName(), client.getTCPPort(), client.getTCPPort2(),
                        client.getUDPPort(), client.getOS());
                // LogPanel.log("Network interface set to auto-detection (IP: " + newIP + ")");
            }
        }

        // 清除客戶端列表並重新發現
        clearClientList();
        multicastHello();

        // 更新 GUI 客戶端列表
        if (GUI != null && SendFileGUI.INSTANCE != null && SendFileGUI.INSTANCE.getClientPanel() != null) {
            SwingUtilities.invokeLater(() -> SendFileGUI.INSTANCE.getClientPanel().refreshGuiListOnly());
        }
        // 在网卡切换后重启监听线程
        if (listenerSocket != null && !listenerSocket.isClosed()) listenerSocket.close();
        listenThread = new Thread(() -> startMulticastListener(), "multicast-listener-thread");
        listenThread.start();
    }

    public static String getIPFromNetworkInterface(NetworkInterface ni) {
        try {
            for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                    return addr.getHostAddress();
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting IP from network interface: " + e.getMessage());
        }
        return null;
    }

    public static NetworkInterface getSelectedNetworkInterface() {
        return selectedNetworkInterface;
    }

    public static boolean isUsingAutoDetection() {
        return useAutoDetection;

    }
}
