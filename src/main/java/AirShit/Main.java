package AirShit; // 定義套件 AirShit

import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.nio.charset.StandardCharsets;
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別
import java.util.concurrent.atomic.AtomicLong; // 引入原子長整數類別
import javax.swing.*; // 引入 Swing 圖形界面相關類別
import java.awt.Font; // 引入 AWT Font類別

public class Main { // 定義 Main 類別
    static Random random = new Random(); // 建立隨機數生成器
    public static SendFileGUI GUI;

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

    public static final int DISCOVERY_PORT = 50000; // Or any other unused port

    static {
        String userName;
        try { // 嘗試取得本機主機名稱
            userName = InetAddress.getLocalHost().getHostName(); // 取得主機名稱並指定給 USER_NAME
        } catch (UnknownHostException e) { // 異常處理：未知主機
            userName = System.getProperty("user" + UUID.randomUUID().toString().substring(0, 8)); // 使用隨機字串作為使用者名稱
        }
        client = new Client(getNonLoopbackIP(), userName, getFreeTCPPort(), DISCOVERY_PORT,
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
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                    continue;
                String name = ni.getDisplayName().toLowerCase();
                // skip Hyper-V, WFP filter drivers, virtual adapters
                if (name.contains("hyper-v") || name.contains("virtual") || name.contains("filter")
                        || name.contains("vmware"))
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        System.out.println("Picked Wi-Fi IP on " + ni.getDisplayName() + ": "
                                + addr.getHostAddress());
                        System.out.println(name);
                        return addr.getHostAddress();
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        // fallback
        return "127.0.0.1";
    }

    public static InetAddress getMulticastAddress() {
        try {
            return InetAddress.getByName("239.255.42.99"); // Valid multicast address
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static NetworkInterface findCorrectNetworkInterface() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                    continue;
                String name = ni.getDisplayName().toLowerCase();
                // skip Hyper-V, WFP filter drivers, virtual adapters
                if (name.contains("hyper-v") || name.contains("virtual") || name.contains("filter")
                        || name.contains("vmware"))
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        return ni;
                    }
                }
            }

        } catch (SocketException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void multicastHello() {
        try (
                MulticastSocket socket = new MulticastSocket();) {
            InetAddress group = getMulticastAddress();
            if (group == null)
                return;

            byte[] sendData = client.getHelloMessage().getBytes("UTF-8");
            socket.setTimeToLive(32);
            socket.setNetworkInterface(findCorrectNetworkInterface());
            socket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), findCorrectNetworkInterface());

            socket.setLoopbackMode(true); // true to disable loopback, equivalent to IP_MULTICAST_LOOP = false
            // println(sendData.length + " bytes sent to multicast group " +
            // group.getHostAddress() + ":" + DISCOVERY_PORT);
            DatagramPacket packet = new DatagramPacket(
                    sendData, sendData.length, group, DISCOVERY_PORT);
            socket.send(packet);

            socket.close();
        } catch (Exception e) {
            e.printStackTrace(); // Uncommented for better error handling
        }
    }

    public static void startMulticastListener() {
        Thread MultiCast = new Thread(() -> {
            try (
                    MulticastSocket socket = new MulticastSocket(DISCOVERY_PORT);) {
                InetAddress group = getMulticastAddress();
                if (group == null)
                    return;

                // *** CHANGE THIS: Listen on the DISCOVERY_PORT ***
                socket.setTimeToLive(32);

                // --- Improved Interface Selection ---
                NetworkInterface iface = findCorrectNetworkInterface();
                if (iface != null) {
                    socket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), iface);
                }

                byte[] buffer = new byte[1024];
                System.out.println("Multicast listener started on port " + DISCOVERY_PORT);
                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8); // Specify
                                                                                                                  // UTF-8
                    // System.out.println("Received multicast: " + message); // Log received message

                    boolean listChanged = false;

                    if (message.startsWith("HEARTBEAT-")) {
                        String[] parts = message.split("-");
                        if (parts.length >= 5) { // IP, Name, TCPPort, UDPPort(Discovery), OS
                            String clientIp = parts[1];
                            String clientName = parts[2];
                            // Check if client is self
                            if (clientIp.equals(client.getIPAddr()) && clientName.equals(client.getUserName())) {
                                // It's our own heartbeat
                            } else if (!clientList.containsKey(clientName)) {
                                Client tempClient = new Client(clientIp, clientName, Integer.parseInt(parts[3]),
                                        Integer.parseInt(parts[4]), parts[5]); // Assuming parts[4] is discovery port,
                                                                               // parts[5] is OS
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
                        socket.send(reply);

                    } else if (Client.isHelloMessage(message)) { // Assuming Client.isHelloMessage checks format
                        Client tempClient = Client.parseMessage(message);
                        if (tempClient != null) {
                            // Check if client is self OR already known
                            if (tempClient.getIPAddr().equals(client.getIPAddr())
                                    && tempClient.getUserName().equals(client.getUserName())) {
                                // It's our own HELLO message
                            } else if (!clientList.containsKey(tempClient.getUserName())) {
                                clientList.put(tempClient.getUserName(), tempClient);
                                // System.out.println("Main: Added new client from HELLO: " + tempClient.getUserName());
                                listChanged = true;
                                // Respond directly to the sender (unicast)
                                responseNewClient(packet.getAddress(), packet.getPort());
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
        });
        MultiCast.start(); // 啟動多播監聽執行緒
    }

    public static void responseNewClient(InetAddress targetAddr, int targetPort) {
        try (
                DatagramSocket socket = new DatagramSocket();) {

            System.out.println("回應新客戶端: " + targetAddr + ":" + targetPort);
            String helloMessage = client.getHelloMessage();
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
            System.out.println("ACK 已傳送到 " + socket.getInetAddress() + ":" + socket.getPort()); // 輸出 ACK 訊息傳送資訊
        } catch (IOException e) { // 捕捉 I/O 異常
            e.printStackTrace(); // 列印異常資訊
        }
    }

    private static ServerSocket lockSocket; // 用於鎖定應用程式實例
    private static final int SINGLE_INSTANCE_LOCK_PORT = 61808; // 選擇一個不太可能被其他應用程式使用的埠號

    public static void main(String[] args) { // 主方法，程式入口點
        // 1) Force JVM encoding to UTF‑8
        if (!acquireSingleInstanceLock()) {
            JOptionPane.showMessageDialog(null,
                "AirShit is already running.\nOnly one instance is allowed.",
                "Application Already Running",
                JOptionPane.WARNING_MESSAGE);
            System.exit(0);
        }

        System.setProperty("file.encoding", "UTF-8");
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "chcp", "65001")
                .redirectErrorStream(true)
                .inheritIO();
        try {
            pb.start().waitFor();
            System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
            System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

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
                + client.getTCPPort() + " IP: " + client.getIPAddr()); // 輸出使用者名稱
        startMulticastListener(); // Start listening first

        FileReceiver fileReceiver = new FileReceiver(client.getTCPPort());

        SwingUtilities.invokeLater(() -> {
            GUI = new SendFileGUI();
        });

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
                    GUI.recvPanel.getLabel().setVisible(false);
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
                });

            }
        };
        new Thread(() -> {
            try {
                fileReceiver.start(cb);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "file-receiver-thread").start();
        multicastHello(); // Then announce yourself
        new Thread(() -> { // 建立新執行緒以檢查客戶端存活狀態
            while (true) { // 無限迴圈檢查存活狀態
                try { // 嘗試檢查存活狀態
                    Thread.sleep(5000); // 每 50 millisecond 秒檢查一次
                    checkAlive(); // 檢查客戶端存活狀態
                } catch (InterruptedException e) { // 捕捉中斷例外
                    e.printStackTrace(); // 列印例外資訊
                }
            }
        }).start(); // 啟動檢查存活狀態的執行緒
        // 註冊一個關閉鉤子，在應用程式退出時釋放鎖
        Runtime.getRuntime().addShutdownHook(new Thread(Main::releaseSingleInstanceLock));

    }

    private static boolean acquireSingleInstanceLock() {
        try {
            // 嘗試在本機回送位址上綁定到指定埠號
            // 如果埠號已被占用 (表示另一個實例正在運行)，則會拋出 IOException
            lockSocket = new ServerSocket(SINGLE_INSTANCE_LOCK_PORT, 1, InetAddress.getLoopbackAddress());
            return true; // 成功獲取鎖
        } catch (IOException e) {
            // 無法獲取鎖，可能是埠號已被占用
            System.err.println("Failed to acquire single instance lock on port " + SINGLE_INSTANCE_LOCK_PORT + ": " + e.getMessage());
            lockSocket = null;
            return false;
        }
    }

    private static void releaseSingleInstanceLock() {
        if (lockSocket != null && !lockSocket.isClosed()) {
            try {
                lockSocket.close();
                System.out.println("Single instance lock released.");
            } catch (IOException e) {
                System.err.println("Error releasing single instance lock: " + e.getMessage());
            }
        }
    }

    public static int getFreeTCPPort() { // 定義取得空閒 TCP 端口的方法
        try (ServerSocket socket = new ServerSocket(0)) { // 建立 ServerSocket 並由系統分配端口
            return socket.getLocalPort(); // 返回分配到的 TCP 端口號
        } catch (IOException e) { // 捕捉 I/O 異常
            throw new RuntimeException("No free TCP port available", e); // 拋出執行例外表示未找到可用端口
        }
    }

    public static void checkAlive() {
        byte[] ping = ("HEARTBEAT-" + client.getHelloMessage()).getBytes(StandardCharsets.UTF_8);
        ArrayList<String> dead = new ArrayList<>();
        // Create a temporary copy of keys to iterate over, to avoid ConcurrentModificationException
        ArrayList<String> currentClientKeys = new ArrayList<>(clientList.keySet());

        for (String name : currentClientKeys) {
            Client c = clientList.get(name);
            if (c == null) continue; // Should not happen if keySet is from clientList

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
}
