package AirShit; // 定義套件 AirShit

import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import javax.swing.*; // 引入 Swing 圖形界面相關類別

public class Main { // 定義 Main 類別
    static Random random = new Random(); // 建立隨機數生成器

    static void println(String s) {
        System.out.println(s);
    }

    private static Client client; // 建立 Client 物件以儲存客戶端資訊

    public static Client getClient() { // 定義取得客戶端資訊的方法
        return client; // 返回客戶端資訊
    }

    public static final int HEARTBEAT_PORT = 50000; // pick any free port
    public static final int DISCOVERY_PORT = 50000; // Or any other unused port
    private static final int CHUNK_SIZE = 64 * 1024 * 1024; // 64KB per chunk

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
    private static final ExecutorService sendExecutor = Executors.newCachedThreadPool();
    private static final ExecutorService recvExecutor = Executors.newFixedThreadPool(4);

    public static Hashtable<String, Client> getClientPorts() { // 定義取得客戶端端口的方法
        return clientList; // 返回客戶端哈希表
    }

    enum SEND_STATUS { // 定義檔案傳送狀態列舉
        SEND_OK, // 傳送正常結束
        SEND_WAITING // 正在等待傳送
    }

    private static AtomicReference<SEND_STATUS> sendStatus = new AtomicReference<>(SEND_STATUS.SEND_OK); // 建立原子參考變數以追蹤傳送狀態
    private static void sendFileInChunks(Socket socket, File file, FileTransferCallback callback) throws Exception {
        String fileName = file.getName();
        long fileLen = file.length();
        int totalChunks = (int)((fileLen + CHUNK_SIZE -1)/CHUNK_SIZE);

        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        InputStream fis = new FileInputStream(file);
        byte[] buf = new byte[CHUNK_SIZE];
        int read;
        int idx = 0;
        while ((read = fis.read(buf)) != -1) {
            // 1) send a 1‑line header for this chunk
            // format: fileName:chunkIndex:totalChunks:chunkSize
            out.println(String.join(":", fileName,
                                     String.valueOf(idx),
                                     String.valueOf(totalChunks),
                                     String.valueOf(read)));
            out.flush();

            // 2) send the raw bytes
            socket.getOutputStream().write(buf, 0, read);
            socket.getOutputStream().flush();

            // 3) wait for ACK
            while (!receiveACK(socket)) { /* spin until ACK */ }

            idx++;
        }
        fis.close();

        // final callback
        if (callback != null) callback.onComplete(true);
        socket.close();
    }


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
                        System.out.println("Picked Wi‑Fi IP on “" + ni.getDisplayName() + "”: " 
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
            DatagramPacket packet = new DatagramPacket(
                    sendData, sendData.length, group, DISCOVERY_PORT);
            socket.send(packet);

            socket.close();
        } catch (Exception e) {
            // e.printStackTrace();
        }
    }


    public static void startMulticastListener() {
        new Thread(() -> {
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
                    println(message);



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
        //chcp 65001; // 設定命令提示字元編碼為 UTF-8
        try {
            System.setOut(new java.io.PrintStream(System.out, true, "UTF-8"));
            System.setErr(new java.io.PrintStream(System.err, true, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "chcp", "65001")
                            .redirectErrorStream(true)
                            .inheritIO()
                            .start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }
    
        
        System.setProperty("file.encoding", "UTF-8"); // 設定檔案編碼為 UTF-8
        sendStatus.set(SEND_STATUS.SEND_OK); // 設定檔案傳送初始狀態
        System.out.println("使用者名稱: " + client.getUserName() + " UDP: " + client.getUDPPort() + " TCP: "
                + client.getTCPPort() + " IP: " + client.getIPAddr()); // 輸出使用者名稱
        startMulticastListener(); // Start listening first
    
        sendStatus.set(SEND_STATUS.SEND_OK);
    
        // startHeartbeatResponder(); // 啟動心跳回應器
        SwingUtilities.invokeLater(() -> {
            new SendFileGUI();
            new Thread(() -> Main.receiveFile()).start();
        }); // 建立並顯示檔案傳送介面
        multicastHello(); // Then announce yourself
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

    public static void receiveFile() { // 定義接收檔案的方法
        
        try (ServerSocket serverSocket = new ServerSocket(client.getTCPPort())) { // 建立 ServerSocket 以接收檔案
            System.out.println("等待檔案傳送..."); // 輸出等待訊息
            while (true) { // 無限迴圈接收檔案
                Socket socket = serverSocket.accept(); // 接受來自客戶端的連線請求
                System.out.println("接收到檔案傳送請求來自 " + socket.getInetAddress() + ":" + socket.getPort()); // 輸出接收請求的客戶端資訊
                // 詢問使用者是否要接收檔案
                int response = JOptionPane.showConfirmDialog(null, "是否要接收檔案？", "檔案傳送請求", JOptionPane.YES_NO_OPTION); // 顯示確認對話框詢問使用者是否接收檔案
                if (response != JOptionPane.YES_OPTION) { // 如果使用者選擇不接收檔案
                    socket.close(); // 關閉連線
                    continue; // 繼續等待下一個檔案傳送請求
                }
                // 如果使用者選擇接收檔案，則開始接收檔案
                System.out.println("開始接收檔案..."); // 輸出開始接收檔案的訊息
                // socket.send("ACK".getBytes(StandardCharsets.UTF_8)); // 傳送 ACK 訊息給客戶端
                sendACK(socket); // 使用 sendACK 方法傳送 ACK 訊息給客戶端
                // 設定傳送狀態為等待中
                sendStatus.set(SEND_STATUS.SEND_WAITING); // 設定傳送狀態為等待中
                
                // 這裡可以使用 ExecutorService 來處理多個連線
                recvExecutor.execute(() -> { // 使用執行緒池處理接收的檔案
                    try {
                        handleIncomingChunks(socket); // 處理接收到的檔案片段
                    } catch (Exception e) {
                        e.printStackTrace(); // 列印例外資訊
                    }
                });
                // handleIncomingChunks(socket); // 處理接收到的檔案片段
                socket.close(); // 關閉連線
                sendStatus.set(SEND_STATUS.SEND_OK); // 設定傳送狀態為正常結束
            }
        } catch (IOException e) { // 捕捉 I/O 異常
            e.printStackTrace(); // 列印異常資訊
        }
    }
    private static void handleIncomingChunks(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(socket.getInputStream()));
        AtomicInteger receivedCount = new AtomicInteger(0);
        int totalChunks = -1;
        String fileName = null;

        // loop until we've got all chunks
        while (true) {
            String hdr = reader.readLine();
            if (hdr == null) throw new IOException("Unexpected EOF");
            // parse header
            String[] parts = hdr.split(":");
            // parts = {fileName, chunkIdx, totalChunks, chunkSize}
            fileName    = parts[0];
            int idx     = Integer.parseInt(parts[1]);
            totalChunks = Integer.parseInt(parts[2]);
            int size    = Integer.parseInt(parts[3]);

            // read exactly size bytes
            byte[] buf = new byte[size];
            int pos = 0;
            InputStream is = socket.getInputStream();
            while (pos < size) {
                int r = is.read(buf, pos, size - pos);
                if (r < 0) throw new IOException("EOF on chunk data");
                pos += r;
            }

            // write to .part file
            File part = new File(fileName + ".part" + idx);
            try (FileOutputStream fos = new FileOutputStream(part)) {
                fos.write(buf);
            }

            // ACK back
            sendACK(socket);

            if (receivedCount.incrementAndGet() == totalChunks) {
                break;
            }
        }

        // merge .part files
        mergeChunks(fileName, totalChunks);
        socket.close();
    }
    private static void mergeChunks(String fileName, int totalChunks) throws IOException {
        try (FileOutputStream out = new FileOutputStream(fileName, false)) {
            byte[] buf = new byte[CHUNK_SIZE];
            for (int i = 0; i < totalChunks; i++) {
                File part = new File(fileName + ".part" + i);
                try (FileInputStream in = new FileInputStream(part)) {
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                }
                part.delete();
            }
        }
    }

    private static void deleteChunkFiles(File f) {
        String base = f.getName();
        File dir = f.getParentFile();
        for (File c : dir.listFiles((d,n)-> n.startsWith(base+".part"))) {
            c.delete();
        }
    }


    public static boolean sendFileToUser(String selectedUserName, File[] files, FileTransferCallback callback) {
        Client targetClient = clientList.get(selectedUserName); // 取得目標客戶端資訊
        if (targetClient == null) { // 如果目標客戶端不存在
            System.out.println("目標客戶端不存在"); // 輸出錯誤訊息
            return false; // 返回傳送失敗
        }
        try { // 嘗試建立 TCP 連線以傳送檔案
            Socket socket = new Socket(targetClient.getIPAddr(), targetClient.getTCPPort()); // 建立 TCP 連線
            System.out.println("連線到 " + targetClient.getIPAddr() + ":" + targetClient.getTCPPort()); // 輸出連線資訊
            // 等待ACK
            if (!receiveACK(socket)) { // 等待接收 ACK 訊息
                System.out.println("未收到 ACK，傳送失敗"); // 輸出錯誤訊息
                socket.close(); // 關閉連線
                return false; // 返回傳送失敗
            }
            System.out.println("收到 ACK，開始傳送檔案"); // 輸出接收到 ACK 訊息
            
            for (File file : files) { // 遍歷所有檔案
                sendFileInChunks(socket, file, callback); // 傳送檔案
                deleteChunkFiles(file); // 刪除暫存檔案
            }
            socket.close(); // 關閉連線
            return true; // 返回傳送成功
        } catch (Exception e) { // 捕捉所有例外
            e.printStackTrace(); // 列印例外資訊
            return false; // 返回傳送失敗
        }
    }

    // Overload for backward compatibility


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
