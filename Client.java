package AirShit;
//  HelloMessage = IPAddr + ":" + USER_NAME + ":" + TCP_PORT + ":" + OS ;

import java.util.Hashtable;

class Client {
    private static String IPAddr;
    private static int TCP_PORT;
    private static int UDP_PORT;
    private static String USER_NAME;
    private static String OS;
    public Client(String IPAddr , String userName, int TCP_PORT , int UDP_PORT ,  String os) {
        this.IPAddr = IPAddr;
        this.USER_NAME = userName;
        this.TCP_PORT = TCP_PORT;
        this.UDP_PORT = UDP_PORT;
        this.OS = os;
    }
    public Client() {
        this.IPAddr = null;
        this.USER_NAME = null;
        this.TCP_PORT = 0;
        this.UDP_PORT = 0;
        this.OS = null;
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
    public void setUDPPort(int UDP_PORT) {
        this.UDP_PORT = UDP_PORT;
    }
    public void setTCPPort(int TCP_PORT) {
        this.TCP_PORT = TCP_PORT;
    }
    public void setUserName(String USER_NAME) {
        this.USER_NAME = USER_NAME;
    }
    public void setOS(String OS) {
        this.OS = OS;
    }
    public void setIPAddr(String IPAddr) {
        this.IPAddr = IPAddr;
    }

    Hashtable<String, Client> clientList = new Hashtable<>(); // 建立存放客戶端資訊的哈希表


    public String getHelloMessage() { // 定義取得 Hello 訊息的方法
        return this.getIPAddr() + ":" + this.getUserName() + ":" + this.getTCPPort() + ":" + this.getUDPPort() + ":" + this.getOS(); // 組合並返回 Hello 訊息字串
    }

    // 解析訊息
    public static boolean parseMessage(String message, Client client) {
        String[] parts = message.split(":");
        // 檢查訊息格式是否正確
        // IPAddr + ":" + USER_NAME + ":" + TCP_PORT + ":" + UDP_PORT + ":" + OS; // 組合並返回 Hello 訊息字串
        if (parts.length == 5) {
            client.setIPAddr(parts[0]);
            client.setUserName(parts[1]);
            client.setTCPPort(Integer.parseInt(parts[2]));
            client.setUDPPort(Integer.parseInt(parts[3]));
            client.setOS(parts[4]);
            return true;
        } else {
            System.out.println("無效的訊息格式");
            return false;
        }
    }
    public String getIPAddr() {
        return IPAddr;
    }
}