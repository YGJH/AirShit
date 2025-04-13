package AirShit; // 定義套件 AirShit
import java.io.*; // 引入輸入輸出相關類別
import java.net.*; // 引入網路相關類別
import java.util.*; // 引入工具類別
import java.util.concurrent.atomic.AtomicReference; // 引入原子參考類別
import javax.swing.*; // 引入 Swing 圖形界面相關類別

public class Main { // 定義 Main 類別
    private static int TCP_PORT;  // 定義 TCP 端口變數
    private static int UDP_PORT; // 定義 UDP 端口變數
    static { // 靜態初始化區塊，用以設定端口號
        TCP_PORT = getFreeTCPPort(); // 取得可用的 TCP 端口
        UDP_PORT = UDP_PORT_Manager.getFreeUDPPort(); // 取得可用的 UDP 端口
    }
    private static String IPAddr; // 定義本機 IP 變數
    private static final String OS = System.getProperty("os.name"); // 取得作業系統名稱
    private static String USER_NAME ; // 定義使用者名稱變數
    static{ // 靜態初始化區塊，用以設定使用者名稱
        try { // 嘗試取得本機主機名稱
            USER_NAME = InetAddress.getLocalHost().getHostName(); // 取得主機名稱並指定給 USER_NAME
        } catch (UnknownHostException e) { // 異常處理：未知主機
            USER_NAME = System.getProperty("user"+UUID.randomUUID().toString().substring(0, 8)); // 使用隨機字串作為使用者名稱
        }
    }
    
    private static Hashtable<String, Client> clientPorts = new Hashtable<>(); // 建立存放客戶端資訊的哈希表
    public static Hashtable<String, Client> getClientPorts() { // 定義取得客戶端哈希表的方法
        return clientPorts; // 返回客戶端哈希表
    }
    
    enum SEND_STATUS { // 定義檔案傳送狀態列舉
        SEND_OK, // 傳送正常結束
        SEND_WAITING // 正在等待傳送
    }
    
    private static AtomicReference<SEND_STATUS> sendStatus = new AtomicReference<>(SEND_STATUS.SEND_OK); // 建立原子參考變數以追蹤傳送狀態

    public static String getNonLoopbcakIP() { // 定義取得非迴圈 IP 的方法
        try { // 嘗試獲取網路介面資訊
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); // 取得所有網路介面
            while (interfaces.hasMoreElements()) { // 遍歷所有網路介面
                NetworkInterface ni = interfaces.nextElement(); // 取得一個網路介面
                Enumeration<InetAddress> addresses = ni.getInetAddresses(); // 取得該介面的所有位址
                while (addresses.hasMoreElements()) { // 遍歷所有位址
                    InetAddress addr = addresses.nextElement(); // 取得一個位址
                    if (!addr.isLoopbackAddress() && addr.isSiteLocalAddress()) { // 判斷是否為非迴圈且本地位址
                        return addr.getHostAddress(); // 返回該 IP 位址
                    }
                }
            }
        } catch (SocketException e) { // 捕捉網路異常
            e.printStackTrace(); // 列印異常資訊
        }
        return "127.0.0.1"; // 返回預設 IP 位址
    }
    private static InetAddress getBroadcastAddress() { // 定義取得廣播位址的方法
        try { // 嘗試獲取網路介面資訊
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); // 取得所有網路介面
            while (interfaces.hasMoreElements()) { // 遍歷所有網路介面
                NetworkInterface ni = interfaces.nextElement(); // 取得一個網路介面
                if (!ni.isLoopback() && ni.isUp()) { // 判斷網路介面是否啟用且非迴圈
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) { // 遍歷該介面的位址資訊
                        InetAddress broadcast = ia.getBroadcast(); // 取得廣播位址
                        if (broadcast != null) { // 如果廣播位址不為空
                            return broadcast; // 返回該廣播位址
                        }
                    }
                }
            }
        } catch (SocketException e) { // 捕捉網路異常
            e.printStackTrace(); // 列印異常資訊
        }
        return null; // 未找到廣播位址則返回 null
    }

    public static void broadCastHello() { // 定義廣播 Hello 訊息的方法
        String helloMessage = getHelloMessage(); // 取得 Hello 訊息字串
        byte[] sendData = helloMessage.getBytes(); // 將訊息轉換成位元組陣列
        InetAddress broadcast = getBroadcastAddress(); // 取得廣播位址
        if(broadcast == null) { // 如果沒有取得廣播位址
            System.out.println("No broadcast address found, aborting broadcast."); // 輸出錯誤訊息
            return; // 結束廣播
        }
        
        for (int port : UDP_PORT_Manager.UDP_PORT) { // 迭代指定範圍內的所有埠
            if (port == UDP_PORT) continue; // 忽略已使用的 UDP 端口
            try { // 嘗試傳送廣播訊息
                // System.err.println("Broadcasting to " + broadcast.getHostAddress() + ":" + port); // 輸出廣播訊息
                DatagramSocket socket = new DatagramSocket(); // 建立 DatagramSocket
                socket.setBroadcast(true); // 設定為廣播模式
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, broadcast, port); // 建立 DatagramPacket 資料包
                socket.send(sendPacket); // 傳送資料包
                socket.close(); // 關閉 DatagramSocket
            } catch (Exception e) { // 捕捉傳送過程中的例外
                // 忽略例外處理
            }
        }
    }
    public static void responseNewClient(Client client) { // 定義回應新客戶端的方法
        try { // 嘗試建立回應訊息
            DatagramSocket udpSocket = new DatagramSocket(); // 建立新的 DatagramSocket 用於傳送回應
            udpSocket.setBroadcast(true); // 設定為廣播模式
            byte[] sendData = getHelloMessage().getBytes(); // 取得 Hello 訊息並轉換為位元組陣列
            InetAddress clientAddress = InetAddress.getByName(client.getIPAddr()); // 取得客戶端的 IP 物件
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, client.getUDPPort()); // 建立傳送用的 DatagramPacket
            System.out.println("Responded to client at " + client.getIPAddr() + ":" + client.getUDPPort()); // 輸出回應訊息
            udpSocket.send(sendPacket); // 傳送資料包
            udpSocket.close(); // 關閉 DatagramSocket
        } catch (Exception e) { // 捕捉例外
            e.printStackTrace(); // 列印例外資訊
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
        IPAddr = getNonLoopbcakIP(); // 取得並設定本機的非迴圈 IP 位址
        sendStatus.set(SEND_STATUS.SEND_OK); // 設定檔案傳送初始狀態
        System.out.println("IP 地址: " + IPAddr + " TCP端口號: " + TCP_PORT + " UDP端口號: " + UDP_PORT + " 使用者名稱: " + USER_NAME + " 作業系統: " + OS); // 輸出系統資訊
        new Thread(() -> new Main().UDPServer()).start(); // 建立新執行緒並啟動 UDP 伺服器
        broadCastHello(); // 廣播 Hello 訊息
        new Thread(() -> new Main().receiveFile()).start(); // 建立新執行緒並啟動檔案接收服務
        SwingUtilities.invokeLater(() -> { // 於事件分派執行緒中啟動 GUI
            new SendFileGUI(); // 建立並顯示檔案傳送介面
        });
    }
    public static int getFreeTCPPort() { // 定義取得空閒 TCP 端口的方法
        try (ServerSocket socket = new ServerSocket(0)) { // 建立 ServerSocket 並由系統分配端口
            return socket.getLocalPort(); // 返回分配到的 TCP 端口號
        } catch (IOException e) { // 捕捉 I/O 異常
            throw new RuntimeException("No free TCP port available", e); // 拋出執行例外表示未找到可用端口
        }
    }
    public static int getFreeUDPPort() { // 定義取得空閒 UDP 端口的方法
        try (DatagramSocket socket = new DatagramSocket(0)) { // 建立 DatagramSocket 並由系統分配端口
            return socket.getLocalPort(); // 返回分配到的 UDP 端口號
        } catch (IOException e) { // 捕捉 I/O 異常
            throw new RuntimeException("No free UDP port available", e); // 拋出執行例外表示未找到可用端口
        }
    }

    public static void receiveFile() { // 定義接收檔案的方法
        try { // 嘗試建立 TCP 伺服器以接收連線
            ServerSocket serverSocket = new ServerSocket(TCP_PORT); // 建立伺服器並監聽 TCP_PORT
            System.out.println("TCP 伺服器啟動，等待客戶端連線 (端口 " + TCP_PORT + ")"); // 輸出伺服器啟動訊息
            while (true) { // 無限迴圈等待客戶端連線
                Socket socket = serverSocket.accept(); // 接受客戶端連線請求
                System.out.println("接收到來自 " + socket.getInetAddress() + " 的連線請求"); // 輸出連線來源資訊
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream())); // 建立輸入串流讀取器
                String header = reader.readLine(); // 讀取傳送的標頭資訊
                System.out.println("接收到的標頭: " + header); // 輸出接收到的標頭資訊
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
    public static void UDPServer() { // 定義 UDP 伺服器方法
        try { // 嘗試啟動 UDP 服務
            DatagramSocket udpSocket = new DatagramSocket(UDP_PORT); // 綁定到指定 UDP 端口
            byte[] recvBuf = new byte[200]; // 建立接收用的緩衝區
            System.out.println("UDP 服務開0始，監聽端口 " + UDP_PORT); // 輸出 UDP 服務啟動訊息
            while (true) { // 無限迴圈等待接收 UDP 訊息
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length); // 建立 DatagramPacket 用於接收資料
                udpSocket.receive(packet); // 接收 UDP 資料包
                String message = new String(packet.getData(), 0, packet.getLength()); // 解析接收到的訊息
                System.out.println("收到 UDP 訊息: " + message + "，來自 " + packet.getAddress()); // 輸出接收到的 UDP 訊息與來源
                Client client = new Client(); // 建立新的 Client 物件
                if (Client.parseMessage(message, client)) { // 解析訊息並填入 Client 物件
                    if(client.getIPAddr().equals(IPAddr)
                    && (client.getTCPPort() == TCP_PORT || client.getUDPPort() == UDP_PORT) || clientPorts.containsKey(client.getUserName())) { // 檢查是否為本機或已存在的客戶端
                        continue; // 若為本機或已存在的客戶端則跳過
                    }
                    System.out.println("ip: " + client.getIPAddr() + " 端口號: " + client.getTCPPort() + " 使用者名稱: " + client.getUserName() + " 作業系統: " + client.getOS()); // 輸出客戶端資訊
                    System.out.println("客戶端名稱: " + client.getUserName()); // 輸出客戶端名稱
                    clientPorts.put(client.getUserName(), client); // 將新客戶端加入哈希表
                    Thread.sleep(10); // 暫停短暫時間
                    responseNewClient(client); // 回應新客戶端
                }
            }
        } catch (Exception e) { // 捕捉例外
            e.printStackTrace(); // 列印例外資訊
        }
    }
    public static boolean sendFileToUser(String selectedUser, File file) { // 定義傳送檔案給指定使用者的方法
        Client client = clientPorts.get(selectedUser); // 從哈希表中取得指定用戶的資訊
        if (client == null) { // 如果未找到該用戶
            System.out.println("Client not found: " + selectedUser); // 輸出找不到用戶訊息
            return false; // 返回失敗
        }
        try { // 嘗試開始檔案傳送程序
            if (sendStatus.get() == SEND_STATUS.SEND_WAITING) { // 如果已有檔案正在傳送
                System.out.println("Currently, a file is being transferred."); // 輸出當前傳送中訊息
                return false; // 返回失敗
            }
            // Compress the file before sending
            File originalFile = file;
            // File compressedFile = File.createTempFile("compressed_", ".gz");
            
            Socket socket = new Socket(client.getIPAddr(), client.getTCPPort()); // 建立與接收者之間的 TCP 連線
            System.out.println("Starting to send file: " + originalFile.getName() + " to " + client.getIPAddr() + ":" + client.getTCPPort()); // 輸出開始傳送檔案訊息
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true); // 建立輸出串流以傳送檔案標頭
            out.println(originalFile.getName() + ":" + originalFile.length()); // 傳送檔案名稱與大小
            out.flush(); // 清空輸出串流
            
            short cnt = 0; // 初始化等待 ACK 的次數
            while(recevieACK(socket) == false && cnt < 3) { // 嘗試等待 ACK 回覆，最多三次
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

    public void printHashTable(Hashtable<String, Client> clientPorts) { // 定義列印客戶端哈希表的方法
        System.out.println("目前有 " + clientPorts.size() + " 個已連線的客戶端: "); // 輸出已連線的客戶端數量
        for (String key : clientPorts.keySet()) { // 遍歷客戶端哈希表
            System.out.println("使用者名稱: " + key + ", 端口號: " + clientPorts.get(key).getTCPPort() + ", 作業系統: " + clientPorts.get(key).getOS()); // 輸出每個客戶端的詳細資訊
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
            return "ACK".equals(ack);
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }    
    public static String getHelloMessage() { // 定義取得 Hello 訊息的方法
        return IPAddr + ":" + USER_NAME + ":" + TCP_PORT + ":" + UDP_PORT + ":" + OS; // 組合並返回 Hello 訊息字串
    }

}
