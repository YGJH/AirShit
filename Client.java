package AirShit;
//  HelloMessage = IPAddr + ":" + userName + ":" + TCPPort + ":" + OS ;

public class Client {
    private String IPAddr;
    private int TCPPort;
    private int UDPPort;
    private String userName;
    private String OS;
    public Client(String IPAddr , String userName, int TCPPort , int UDPPort ,  String OS) {
        this.IPAddr = IPAddr;
        this.userName = userName.replaceAll("-", "_");
        this.TCPPort = TCPPort;
        this.UDPPort = UDPPort;
        this.OS = OS;
    }

    public void setUDPPort(int UDPPort) {
        this.UDPPort = UDPPort;
    }
    public void setTCPPort(int TCPPort) {
        this.TCPPort = TCPPort;
    }
    public void setOS(String OS) {
        this.OS = OS;
    }
    public void setIPAddr(String IPAddr) {
        this.IPAddr = IPAddr;
    }
    public void setUserName(String userName) {
        userName = userName.replaceAll("-", "_");
        this.userName = userName;
    }

    public Client(Client client) {
        this.IPAddr = client.getIPAddr();
        this.userName = client.getUserName();
        this.TCPPort = client.getTCPPort();
        this.UDPPort = client.getUDPPort();
        this.OS = client.getOS();
    }
    public String getUserName() {
        return userName;
    }
    public int getTCPPort() {
        return TCPPort;
    }
    public int getUDPPort() {
        return UDPPort;
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
            int TCPPort = Integer.parseInt(parts[2]); // 取得 TCP 埠號
            int UDPPort = Integer.parseInt(parts[3]); // 取得 UDP 埠號
            String OS = parts[4]; // 取得作業系統名稱
            return new Client(IPAddr, userName, TCPPort, UDPPort, OS); // 返回新的 Client 物件
        } else {
            System.out.println("無效的訊息格式");
            return null;
        }
    }
    
    public String getHelloMessage() { // 定義取得 Hello 訊息的方法
        userName = userName.replaceAll("-", "_");
        return IPAddr + "-" + userName + "-" + TCPPort + "-" + UDPPort + "-" + OS; // 組合並返回 Hello 訊息字串
    }
}