package AirShit; // 定義套件 AirShit

import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.nio.charset.StandardCharsets;
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別
import java.util.concurrent.atomic.AtomicLong; // 引入原子長整數類別
import java.util.concurrent.ConcurrentHashMap; // 引入 ConcurrentHashMap
import javax.swing.*; // 引入 Swing 圖形界面相關類別
import java.awt.Font; // 引入 AWT Font類別

public class Main { // 定義 Main 類別
    static Random random = new Random(); // 建立隨機數生成器

    static void println(String s) {
        System.out.println(s);
    }

    private static Client client; // 建立 Client 物件以儲存客戶端資訊

    public static Client getClient() { // 定義取得客戶端資訊的方法
        return client; // 返回客戶端資訊
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
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                    continue;
                String name = ni.getDisplayName().toLowerCase();
                // skip Hyper-V, WFP filter drivers, virtual adapters
                if (name.contains("hyper-v") || name.contains("virtual") || name.contains("filter"))
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        System.out.println("Picked Wi-Fi IP on " + ni.getDisplayName() + ": "
                                + addr.getHostAddress());
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
                if (name.contains("hyper-v") || name.contains("virtual") || name.contains("filter"))
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
        try {
            InetAddress group = getMulticastAddress();
            if (group == null)
                return;

            byte[] sendData = client.getHelloMessage().getBytes();
            MulticastSocket socket = new MulticastSocket();
            socket.setTimeToLive(32);
            socket.setNetworkInterface(findCorrectNetworkInterface());
            socket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), findCorrectNetworkInterface());
            
            socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, false);
            println(sendData.length + " bytes sent to multicast group " + group.getHostAddress() + ":" + DISCOVERY_PORT);
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
            MulticastSocket socket = null;
            try {
                InetAddress group = getMulticastAddress();
                if (group == null)
                    return;

                // *** CHANGE THIS: Listen on the DISCOVERY_PORT ***
                socket = new MulticastSocket(DISCOVERY_PORT);
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

                    String message = new String(packet.getData(), 0, packet.getLength());
                    System.out.println(message);

                    // Check if the message is a heartbeat
                    if ((message).startsWith("HEARTBEAT-")) {
                        String[] parts = message.split("-"); // use dash as delimiter
                        if (clientList.containsKey(parts[1]) == false) {
                            Client tempClient = new Client(parts[1], parts[2], Integer.parseInt(parts[3]),
                                    DISCOVERY_PORT, parts[4]);
                            clientList.put(tempClient.getUserName(), tempClient);
                        }
                        byte[] resp = "ALIVE".getBytes(StandardCharsets.UTF_8);
                        DatagramPacket reply = new DatagramPacket(
                                resp, resp.length,
                                packet.getAddress(), packet.getPort());
                        socket.send(reply);
                        continue;
                    }

                    // Ignore self-sent messages (more robust check needed if multiple local IPs)
                    InetAddress localInetAddress = InetAddress.getByName(client.getIPAddr());
                    if (packet.getAddress().equals(localInetAddress) && packet.getPort() == DISCOVERY_PORT) {
                        continue;
                    }

                    Client tempClient = Client.parseMessage(message);
                    if (tempClient == null)
                        continue;

                    // Check if client is self OR already known (use IP as key if possible)
                    if (tempClient.getIPAddr().equals(client.getIPAddr())
                            || clientList.containsKey(tempClient.getUserName())) {
                        continue;
                    }

                    // Use IP address as the key for consistency
                    clientList.put(tempClient.getUserName(), tempClient);
                    System.out.println(
                            "Discovered client: " + tempClient.getUserName() + " at " + tempClient.getIPAddr());

                    // Respond directly to the sender (unicast)
                    for (int i = 0; i < 3; i++) { // Send hello message 3 times
                        responseNewClient(packet.getAddress(), DISCOVERY_PORT); // Respond to the port the hello came
                                                                                // from
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
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
        });
        MultiCast.start(); // 啟動多播監聽執行緒
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
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
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
        // 1) Force JVM encoding to UTF‑8
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

        sendStatus.set(SEND_STATUS.SEND_OK);
        FileReceiver fileReceiver = new FileReceiver(client.getTCPPort());
        new Thread(() -> {
            try {
                AtomicLong totalExpected = new AtomicLong(0);
                AtomicLong totalReceived = new AtomicLong(0);
                ConcurrentHashMap<String,Long> lastSeen = new ConcurrentHashMap<>();

                fileReceiver.start(new TransferCallback() { // 開始檔案接收
                    @Override
                    public void onStart(long totalBytes) { // 檔案傳送開始時的回調方法
                        println("開始接收檔案，總大小: " + totalBytes + " bytes"); // 輸出檔案大小
                        totalExpected.set(totalBytes); // 設定預期的檔案大小
                    }

                    @Override
                    public void onProgress(long bytesTransferred) { // 檔案傳送進度的回調方法
                        totalReceived.addAndGet(bytesTransferred); // 更新已接收的檔案大小
                        println("已接收: " + totalReceived.get() + " / " + totalExpected.get() + " bytes"); // 輸出已接收的檔案大小
                    }

                    @Override
                    public void onError(Exception e) { // 檔案傳送錯誤的回調方法
                        e.printStackTrace(); // 列印錯誤資訊
                    }
                });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }, "file-receiver-thread").start();
        SwingUtilities.invokeLater(() -> {
            new SendFileGUI();
        });
        multicastHello(); // Then announce yourself
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

    public static boolean receiveACK(Socket socket) {
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
        byte[] ping = ("HEARTBEAT-" + client.getHelloMessage()).getBytes(StandardCharsets.UTF_8); // 取得 Hello 訊息
        ArrayList<String> dead = new ArrayList<>();
        for (Map.Entry<String, Client> e : clientList.entrySet()) {
            String name = e.getKey();
            Client c = e.getValue();
            boolean alive = false;
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setSoTimeout(2000);
                InetAddress addr = InetAddress.getByName(c.getIPAddr());
                ds.send(new DatagramPacket(ping, ping.length, addr, DISCOVERY_PORT)); // 發送 Hello 訊息
                byte[] buf = new byte[64];
                DatagramPacket resp = new DatagramPacket(buf, buf.length);
                ds.receive(resp);
                String reply = new String(resp.getData(), 0, resp.getLength(), StandardCharsets.UTF_8);
                if ("ALIVE".equals(reply))
                    alive = true;
            } catch (IOException ignore) {
                // timeout or error => not alive
            }
            if (!alive) {
                dead.add(name);
            }
        }
        for (String name : dead) {
            clientList.remove(name);
            println("Removed dead client: " + name);
        }
    }
}
