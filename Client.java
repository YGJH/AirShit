package AirShit;
//  HelloMessage = IPAddr + ":" + USER_NAME + ":" + TCP_PORT + ":" + OS ;

class Client {
    private String IPAddr;
    private int TCP_PORT;
    private int UDP_PORT;
    private String USER_NAME;
    private String OS;
    public Client(String IPAddr , String userName, int TCP_PORT , int UDP_PORT ,  String os) {
        this.IPAddr = IPAddr;
        this.USER_NAME = userName.replaceAll("-", "_");
        this.TCP_PORT = TCP_PORT;
        this.UDP_PORT = UDP_PORT;
        this.OS = os;
    }

    public void setUDPPort(int UDP_PORT) {
        this.UDP_PORT = UDP_PORT;
    }
    public void setTCPPort(int TCP_PORT) {
        this.TCP_PORT = TCP_PORT;
    }
    public void setOS(String OS) {
        this.OS = OS;
    }
    public void setIPAddr(String IPAddr) {
        this.IPAddr = IPAddr;
    }
    public void setUserName(String userName) {
        userName = userName.replaceAll("-", "_");
        this.USER_NAME = userName;
    }

    public Client(Client client) {
        this.IPAddr = client.getIPAddr();
        this.USER_NAME = client.getUserName();
        this.TCP_PORT = client.getTCPPort();
        this.UDP_PORT = client.getUDPPort();
        this.OS = client.getOS();
    }
    public String getUserName() {
        return USER_NAME;
    }
    public int getTCPPort() {
        return TCP_PORT;
    }
    public int getUDPPort() {
        return UDP_PORT;
    }
    public String getOS() {
        return OS;
    }
    public String getIPAddr() {
        return IPAddr;
    }

    public static boolean check(Client c1, Client c2) {
        return c1.getUserName().equals(c2.getUserName()) &&
               c1.getIPAddr().equals(c2.getIPAddr()) &&
               c1.getTCPPort() == c2.getTCPPort();
    }

        // 解析訊息
    public static Client parseMessage(String message) {
        String[] parts = message.split("-");
        // 檢查訊息格式是否正確
        // 解析訊息 [IP + User + TCP + UDP + OS]
        if (parts.length == 5) {
            // clean IP address
            String IPAddr = parts[0]; // 取得 IP 位址
            String userName = parts[1]; // 取得使用者名稱
            int TCP_PORT = Integer.parseInt(parts[2]); // 取得 TCP 埠號
            int UDP_PORT = Integer.parseInt(parts[3]); // 取得 UDP 埠號
            String OS = parts[4]; // 取得作業系統名稱
            return new Client(IPAddr, userName, TCP_PORT, UDP_PORT, OS); // 返回新的 Client 物件
        } else {
            System.out.println("無效的訊息格式");
            return null;
        }
    }
    
    public String getHelloMessage() { // 定義取得 Hello 訊息的方法
        USER_NAME = USER_NAME.replaceAll("-", "_");
        return IPAddr + "-" + USER_NAME + "-" + TCP_PORT + "-" + UDP_PORT + "-" + OS; // 組合並返回 Hello 訊息字串
    }
}