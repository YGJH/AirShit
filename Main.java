package AirShit; // 定義套件 AirShit

import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.nio.charset.StandardCharsets;
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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

    public static final int HEARTBEAT_PORT = 50001; // pick any free port
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
            while (!recevieACK(socket)) { /* spin until ACK */ }

            idx++;
        }
        fis.close();

        // final callback
        if (callback != null) callback.onComplete(true);
        socket.close();
    }


    public static String getNonLoopbackIP() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual())
                    continue;
                String d = iface.getDisplayName().toLowerCase();
                String n = iface.getName().toLowerCase();
                if (d.contains("hyper") || n.startsWith("vethernet"))
                    continue;

                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    // Only return IPv4
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        // fallback to localhost IPv4
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
            InetAddress local = InetAddress.getByName(client.getIPAddr());
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual())
                    continue;
                String d = ni.getDisplayName().toLowerCase(),
                        n = ni.getName().toLowerCase();
                if (d.contains("hyper") || n.startsWith("vethernet"))
                    continue;

                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.equals(local))
                        return ni;
                }
            }
        } catch (Exception e) {
            System.err.println("Error finding interface: " + e.getMessage());
        }
        return null;
    }

    public static void multicastHello() {
        try {
            client.setIPAddr(getNonLoopbackIP()); // Set the IP address
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
                InetSocketAddress groupAddr = new InetSocketAddress(group, DISCOVERY_PORT); // Use DISCOVERY_PORT

                // --- Improved Interface Selection ---
                NetworkInterface netIf = findCorrectNetworkInterface();
                if (netIf == null) {
                    System.err.println("Could not find suitable network interface for multicast. Trying default.");
                    socket.joinGroup(groupAddr, null);
                } else {
                    System.out.println("Joining multicast group on interface: " + netIf.getDisplayName() + " ["
                            + netIf.getName() + "]"); // Log name too
                    socket.joinGroup(groupAddr, netIf);
                }
                // --- End Improved Interface Selection ---

                byte[] buffer = new byte[1024];
                System.out.println("Multicast listener started on port: " + DISCOVERY_PORT);

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    // --- Process packet ---
                    String message = new String(packet.getData(), 0, packet.getLength());
                    println(message);
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
        sendStatus.set(SEND_STATUS.SEND_OK); // 設定檔案傳送初始狀態
        System.out.println("使用者名稱: " + client.getUserName() + " UDP: " + client.getUDPPort() + " TCP: "
                + client.getTCPPort() + " IP: " + client.getIPAddr()); // 輸出使用者名稱
        startMulticastListener(); // Start listening first
        multicastHello(); // Then announce yourself

        startHeartbeatResponder(); // 啟動心跳回應器
        SwingUtilities.invokeLater(() -> {
            new SendFileGUI();
            new Thread(() -> Main.receiveFile()).start();
        }); // 建立並顯示檔案傳送介面
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

    public static void startHeartbeatResponder() {
        new Thread(() -> {
            try (DatagramSocket sock = new DatagramSocket(HEARTBEAT_PORT)) {
                byte[] buf = new byte[64];
                while (true) {
                    DatagramPacket recv = new DatagramPacket(buf, buf.length);
                    sock.receive(recv);
                    String msg = new String(recv.getData(), 0, recv.getLength(), StandardCharsets.UTF_8);
                    println(msg);
                    if ((msg).startsWith("HEARTBEAT-")) {
                        String[] parts = msg.split("-"); // use dash as delimiter
                        if (clientList.containsKey(parts[1]) == false) {
                            Client tempClient = new Client(parts[1], parts[2], Integer.parseInt(parts[3]),
                                    DISCOVERY_PORT, parts[4]);
                            clientList.put(tempClient.getUserName(), tempClient);
                        }

                        byte[] resp = "ALIVE".getBytes(StandardCharsets.UTF_8);
                        DatagramPacket reply = new DatagramPacket(
                                resp, resp.length,
                                recv.getAddress(), recv.getPort());
                        sock.send(reply);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                Thread.sleep(2000 + random.nextInt(1000)); // Sleep for 1 second before next heartbeat
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }, "heartbeat-responder").start();
    }

    public static void receiveFile() { // 定義接收檔案的方法
        SendFileGUI.start = false;
        SendFileGUI.receiveFileProgress(0);

        try { // 嘗試建立 TCP 伺服器以接收連線

            ServerSocket serverSocket = new ServerSocket(client.getTCPPort());
            System.out.println("TCP 伺服器啟動，等待客戶端連線 (端口 " + client.getTCPPort() + ")");
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

                if (parts.length != 3) {
                    System.out.println("Invalid header. Closing connection.");
                    socket.close();
                    continue;
                }
                // System.out.println("Header parts: " + header.split("|")[0] ); // 輸出標頭分割後的資訊
                String fileName = parts[0];
                long fileSize = Long.parseLong(parts[1]);
                boolean isFolder = parts[2].equals("isFolder");
                int option = JOptionPane.showConfirmDialog(null,
                        "接受檔案傳輸?\n檔案名稱: " + fileName + "\n檔案大小: " + fileSize + " bytes",
                        "檔案傳輸",
                        JOptionPane.YES_NO_OPTION); // 顯示對話框詢問是否接受檔案
                if (option != JOptionPane.YES_OPTION) { // 如果使用者拒絕接受檔案
                    System.out.println("使用者拒絕接收檔案"); // 輸出拒絕訊息
                    socket.close(); // 關閉連線
                    continue; // 繼續等待下一個連線
                }
                File saveFile ; // 建立檔案物件以儲存接收的檔案
                if(isFolder) {
                    // 如果是資料夾，則建立資料夾
                    JFileChooser fileChooser = new JFileChooser(); // 建立檔案選擇器
                    fileChooser.setDialogTitle("儲存檔案"); // 設定對話框標題
                    fileChooser.setSelectedFile(new File(fileName)); // 設定預設檔案名稱
                    File folder = FileChooserGUI.chooseDirectory();
                    if (!folder.exists()) {
                        folder.mkdirs(); // 建立資料夾
                    }
                    saveFile = new File(folder, fileName); // 設定儲存檔案路徑
                } else {
                    // 如果是檔案，則顯示儲存檔案對話框
                    JFileChooser fileChooser = new JFileChooser(); // 建立檔案選擇器
                    fileChooser.setDialogTitle("儲存檔案"); // 設定對話框標題
                    fileChooser.setSelectedFile(new File(fileName)); // 設定預設檔案名稱
                    int userSelection = fileChooser.showSaveDialog(null); // 顯示儲存檔案對話框
                    if (userSelection != JFileChooser.APPROVE_OPTION) { // 如果使用者取消選擇
                        System.out.println("使用者取消儲存檔案"); // 輸出取消訊息
                        socket.close(); // 關閉連線
                        continue; // 繼續等待下一個連線
                    }
                    saveFile = fileChooser.getSelectedFile(); // 取得使用者選擇的儲存檔案
                }
                sendACK(socket); // 傳送 ACK 訊息以通知傳送者開始資料傳送
                SendFileGUI.receiveFileProgress(0); // 更新接收檔案進度
                SendFileGUI.start = true; // 更新 GUI 狀態
                
                recvExecutor.submit(() -> {
                    try {
                        handleIncomingChunks(socket); // 處理接收的檔案
                    } catch (Exception e) {
                        e.printStackTrace();
                        try { socket.close(); } catch(Exception ignore){}
                    }
                });

                sendStatus.set(SEND_STATUS.SEND_OK); // 更新傳送狀態
                
                SendFileGUI.start = false; // 更新 GUI 狀態
                SendFileGUI.receiveFileProgress(0); // 重置接收檔案進度
            }

        } catch (Exception e) { // 捕捉所有例外
            e.printStackTrace(); // 列印例外資訊
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
        if (files == null || files.length == 0)
            return false;

        Client targetClient = clientList.get(selectedUserName);
        if (targetClient == null) {
            System.out.println("Target client not found: " + selectedUserName);
            return false;
        }

        try {
            if (sendStatus.get() == SEND_STATUS.SEND_WAITING) {
                System.out.println("Currently, a file is being transferred.");
                return false;
            }

            // Prepare file to send - create temp zip for multiple files or directories

            Socket socket = new Socket(targetClient.getIPAddr(), targetClient.getTCPPort());

            for (File f : files) {
                sendExecutor.submit(() -> {
                    try {
                        sendFileInChunks(socket, f, callback);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        // cleanup on failure
                        deleteChunkFiles(f);
                        if (callback!=null) callback.onComplete(false);
                    }
                });
            }
            if (callback != null)
            callback.onComplete(true);
            System.out.println("File sent successfully");
            
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
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
                ds.send(new DatagramPacket(ping, ping.length, addr, HEARTBEAT_PORT));
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
