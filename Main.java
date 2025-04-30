package AirShit; // 定義套件 AirShit

import java.awt.Font; // 引入 AWT Font類別
import java.awt.Dimension; // 引入 AWT Dimension 類別
import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.nio.charset.StandardCharsets;
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別

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
    public static SendFileGUI GUI; // 建立 SendFileGUI 物件以顯示檔案傳送介面
    public static final int HEARTBEAT_PORT = 50001; // pick any free port
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
        try (
            MulticastSocket socket = new MulticastSocket();
        ){
            InetAddress group = getMulticastAddress();
            if (group == null)
                return;

            byte[] sendData = client.getHelloMessage().getBytes("UTF-8");
            socket.setTimeToLive(32);
            socket.setNetworkInterface(findCorrectNetworkInterface());
            socket.joinGroup(new InetSocketAddress(group, DISCOVERY_PORT), findCorrectNetworkInterface());
            
            socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, false);
            // println(sendData.length + " bytes sent to multicast group " + group.getHostAddress() + ":" + DISCOVERY_PORT);
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
                MulticastSocket socket = new MulticastSocket(DISCOVERY_PORT);
            ){
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

                    String message = new String(packet.getData(), 0, packet.getLength());
                    // System.out.println(message);
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
                    if(tempClient.getIPAddr().equals(client.getIPAddr()) && tempClient.getTCPPort() == client.getTCPPort()
                    || clientList.containsKey(tempClient.getUserName())) {
                        continue;
                    }
                    // Use IP address as the key for consistency

                    clientList.put(tempClient.getUserName() , tempClient);
                    // Respond directly to the sender (unicast)
                    responseNewClient(packet.getAddress(), DISCOVERY_PORT); // Respond to the port the hello came

                    // --- End Process packet ---
                }
            } catch (Exception e) {
                e.printStackTrace();
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
        multicastHello(); // Then announce yourself
        
        
        SwingUtilities.invokeLater(() -> {
            GUI = new SendFileGUI();
        }); // 建立並顯示檔案傳送介面
        TransferCallback cb = new TransferCallback() {
            long totalReceived = 0;
            long totalBar = 0;
            @Override
            public void onStart(long totalBytes) {
                totalBar = totalBytes;
                SwingUtilities.invokeLater(() -> SendFileGUI.receiveProgressBar.setVisible(true));
                SwingUtilities.invokeLater(() -> SendFileGUI.receiveProgressBar.setMaximum((int)100));
            }
            @Override
            public void onProgress(long bytesTransferred) {
                totalReceived += bytesTransferred;
                long cumul = totalReceived;
                SwingUtilities.invokeLater(() -> {
                    int pct = (int)(cumul*100/totalBar);
                    SendFileGUI.receiveProgressBar.setValue((int)pct);
                    if (pct % 10 == 0) {
                        GUI.log("%%rProgress: " + pct + "% (" + SendFileGUI.formatFileSize(cumul) + ")");
                    }
                });
            }
            @Override
            public void onError(Exception e) {
                SwingUtilities.invokeLater(() -> {
                    GUI.log("Error: " + e.getMessage());
                    SendFileGUI.receiveProgressBar.setVisible(false);
                });
            }
        };

        Thread receiver = new Thread(() -> {
            try {
                receiveFile(cb); // 接收檔案
            } catch (IOException e) { // 捕捉 I/O 異常
                e.printStackTrace(); // 列印異常資訊
            }
        });
        receiver.setName("Receiver"); // 設定執行緒名稱
        receiver.start();
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



    public static void receiveFile(TransferCallback cb) throws IOException { // 此port 是你本地的port

        // handshake
        try (ServerSocket serverSocket = new ServerSocket(client.getTCPPort())) { // 建立 ServerSocket 以接收檔案
            while (true) {
                // listen for handshake
                boolean isSingle = false;
                String senderUserName = null;
                String fileNames = null;
                String folderName = null;
                int fileCount = 0;
                long totalSize = 0;
                StringBuilder sb = new StringBuilder();
                try (
                        Socket socket = serverSocket.accept();
                        DataInputStream dis = new DataInputStream(socket.getInputStream());) {
                    String handshake = dis.readUTF();
                    String[] parts = handshake.split("\\|");
                    if (parts.length < 3) {
                        System.err.println("無效的 handshake 訊息： " + handshake);
                        continue;
                    }
                    // isSingle|SenderUserName|file.getName()|file.length();
                    // isMulti|SenderUserName|folderName|file.getName()|file.length();
                    println("接收到 handshake 訊息： " + handshake);
                    if (parts[0].equals("isSingle")) {
                        isSingle = true;
                        senderUserName = parts[1];
                        fileCount = 1;
                        fileNames = parts[2];
                        totalSize = Long.parseLong(parts[3]);
                        sb.append(fileNames);
                        println("單檔傳送：SenderUserName=" + senderUserName + ", fileNames=" + fileNames + ", totalSize="
                                + totalSize);
                    } else if (parts[0].equals("isMulti")) {
                        senderUserName = parts[1];
                        folderName = parts[2];
                        fileCount = parts.length - 4;
                        totalSize = Long.parseLong(parts[parts.length - 1]);
                        for (int i = 3; i < parts.length - 1; i++) {
                            sb.append(parts[i]).append("\n");
                        }
                        println("多檔傳送：SenderUserName=" + senderUserName + ", folderName=" + folderName);
                    } else {
                        System.err.println("無效的 handshake 類型： " + parts[0]);
                        continue;
                    }
                    // ask user to accept the file
                    // build a scrollable text area for the file list
                    StringBuilder listText = new StringBuilder();
                    if (isSingle) {
                        listText.append(fileNames);
                    } else {
                        for (int j = 3; j < parts.length - 1; j++) {
                            listText.append(parts[j]).append("\n");
                        }
                    }
                    String info = "Sender: " + senderUserName
                            + "\nFolder: " + folderName
                            + "\nTotal Size: " + totalSize + " bytes\n\nFiles:\n";
                    JTextArea ta = new JTextArea(info + listText.toString());
                    ta.setEditable(false);
                    ta.setLineWrap(true);
                    ta.setWrapStyleWord(true);

                    // wrap it in a scroll pane
                    JScrollPane pane = new JScrollPane(ta,
                            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                    pane.setPreferredSize(new Dimension(400, 200));

                    // show the confirm dialog with the scroll pane as the message component
                    int response = JOptionPane.showConfirmDialog(
                            null,
                            pane,
                            "檔案傳送 — 接收確認",
                            JOptionPane.YES_NO_OPTION);
                    if (response != JOptionPane.YES_OPTION) {
                        try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                            dos.writeUTF("REJECT");
                            dos.flush();
                            continue;
                        } catch (IOException e) {
                            System.err.println("無法與 Sender 通訊：");
                            e.printStackTrace();
                        }
                        System.out.println("使用者拒絕接收檔案。");
                        continue;

                    }

                    // get output file path
                    String outputFilePath = FolderSelector.selectFolder();
                    if (outputFilePath == null) {
                        System.out.println("使用者取消選擇資料夾。");
                        try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                            dos.writeUTF("REJECT");
                            dos.flush();
                        } catch (IOException e) {
                            System.err.println("無法與 Sender 通訊：");
                            e.printStackTrace();
                        }
                        continue;
                    }
                    if (!isSingle) {
                        outputFilePath = outputFilePath + "\\" + folderName;
                        println("outputFilePath: " + outputFilePath);
                        File folder = new File(outputFilePath);
                        if (!folder.exists()) {
                            folder.mkdirs(); // Create the directory if it doesn't exist
                            println("已建立資料夾：" + folderName);
                        }
                    }
                    // send accept message to sender
                    cb.onStart(totalSize); // 開始接收檔案
                    Main.sendStatus.set(SEND_STATUS.SEND_WAITING);
                    try (DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {
                        dos.writeUTF("ACK");
                        dos.flush();
                    } catch (IOException e) {
                        System.err.println("無法與 Sender 通訊：");
                        e.printStackTrace();
                    }
                    final String outPutPath = outputFilePath;
                    // println("已接受檔案傳送。");

                    // notify sender to start sending the file
                    println(fileCount + " 個檔案，總大小：" + totalSize + " bytes");
                    for (int i = 0; i < fileCount; i++) {
                        try ( Socket sock = serverSocket.accept();
                              DataInputStream  ddis = new DataInputStream(sock.getInputStream());
                              DataOutputStream dos = new DataOutputStream(sock.getOutputStream()) ) {
                            // --- 1) handshake ---
                            String han = ddis.readUTF();
                            String[] header = han.split("\\|");
                            String  fname = header[0];
                            long    fsize = Long.parseLong(header[1]);
                            // cb.onStart(totalSize); // 開始接收檔案
                            // send accept message to sender
                            dos.writeUTF("ACK");  dos.flush();
                            // --- 2) for each file, 直接在同一條連線上連續送 header + data + 等 OK ---
                            for (int j = 0 ; j < fileCount ; j++) {
                                // a) 讀 header "filename|size"
                                String[] h = dis.readUTF().split("\\|");
                                String  fileName = h[0];
                                long    fileSize = Long.parseLong(h[1]);
                                // b) 回 ACK
                                dos.writeUTF("ACK");  dos.flush();
                                // c) 寫檔
                                try ( FileOutputStream fos = new FileOutputStream(outputFilePath + "\\" + fileName) ) {
                                    byte[] buf = new byte[8192];
                                    long   recv = 0;
                                    while (recv < fileSize) {
                                        int r = dis.read(buf,0,(int)Math.min(buf.length, fileSize-recv));
                                        fos.write(buf,0,r);
                                        recv += r;
                                        cb.onProgress(r);
                                    }
                                }

                                dos.write("OK".getBytes()); // 傳送 OK 訊息
                                dos.flush(); // 清空輸出串流
                                println("檔案接收完成：" + fileName + " (" + fileSize + " bytes)");
                            }
                            // 全部檔案收完，回到最頂端繼續下一次 handshake
                        } catch (IOException e) {
                            System.err.println("檔案接收失敗：");
                            e.printStackTrace();
                        }
                    }
                } catch (IOException e) {
                    System.err.println("無法連線到 Sender：");
                    e.printStackTrace();
                }
                Main.sendStatus.set(SEND_STATUS.SEND_OK);
            }
        }
    }
    public static void clearClientList() {
        clientList.clear(); // 清空客戶端列表
    }
    public static void sendFiles(File[] files , Client targClient , String folderName , TransferCallback callback) throws IOException, InterruptedException {

        // handshake
        StringBuilder sb = new StringBuilder();
        long totalSize = 0;
        boolean isSingleFile = files.length == 1;
        if(isSingleFile) {
            sb.append("isSingle|");
            sb.append(client.getUserName()).append("|").append(files[0].getName()).append("|").append(files[0].length());
        } else {
            sb.append("isMulti|");
            sb.append(client.getUserName() + "|" + folderName);
            for (File f : files) {
                totalSize += f.length();
                sb.append("|").append(f.getName());
            }
            sb.append("|").append(totalSize);
        }

        // 連線到 Receiver
        try (Socket socket = new Socket(targClient.getIPAddr(), targClient.getTCPPort());
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            // 傳送 handshake 訊息
            dos.writeUTF(sb.toString());
            dos.flush();
            println("傳送 handshake 訊息： " + sb.toString());
            // 等待 Receiver 確認接收檔案
            String response = dis.readUTF();
            if (response.equals("ACK")) {
                println("Receiver 確認接收檔案。");
            } else {
                System.err.println("Receiver 無法接收檔案，請稍後再試。");
                return;
            }

        } catch (IOException e) {
            System.err.println("無法連線到 Receiver：");
            e.printStackTrace();
            return;
        }

        callback.onStart(totalSize);
        for (File file : files) {
            // notify user
            String fileName = file.getName();
            String fileSize = String.valueOf(file.length());
            try (Socket socket2 = new Socket(targClient.getIPAddr(), targClient.getTCPPort());
                DataOutputStream dos = new DataOutputStream(socket2.getOutputStream());
                DataInputStream  dis = new DataInputStream(socket2.getInputStream())) {
        
                // 1) send the file‑name|size header
                dos.writeUTF(fileName + "|" + fileSize);
                dos.flush();
        
                // 2) wait for ACK on the same socket
                String response = dis.readUTF();
                if (!"ACK".equals(response)) {
                    System.err.println("Receiver 無法接收檔案：" + fileName);
                    return;
                } else {
                    println("receiver 已開始接收檔案" + fileName);
                }
            
                // 3) now kick off your SendFile/ChunkSender against socket2
                // send the file
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; // 8KB buffer
                    int bytesRead;
                    long totalBytesRead = 0;
                    while ((bytesRead = fis.read(buffer)) != -1 && totalBytesRead < file.length()) {
                        dos.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;
                        callback.onProgress(bytesRead); // 更新進度
                    }
                } catch (IOException e) {
                    System.err.println("檔案傳送失敗：" + fileName);
                    e.printStackTrace();
                }
                response = dis.readUTF();
                if("OK".equals(response)) {
                    continue;
                } 
                
            } catch (Exception e) {
                callback.onError(e);
            }
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
        byte[] ping = ("HEARTBEAT-" + client.getHelloMessage()).getBytes(StandardCharsets.UTF_8); // 取得 Hello 訊息
        ArrayList<String> dead = new ArrayList<>();
        for (Map.Entry<String, Client> e : clientList.entrySet()) {
            String name = e.getKey();
            Client c = e.getValue();
            boolean alive = false;
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setSoTimeout(2000);
                InetAddress addr = InetAddress.getByName(c.getIPAddr());
                ds.send(new DatagramPacket(ping, ping.length, addr, DISCOVERY_PORT)); // 發送心跳訊息
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
