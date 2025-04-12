package AirShit;
//  HelloMessage = IPAddr + ":" + USER_NAME + ":" + TCP_PORT + ":" + OS ;

class Client {
    private static String IPAddr;
    private static int TCP_PORT;
    private static int UDP_PORT;
    private static String USER_NAME;
    private static String OS;
    public Client(String IPAddr , String userName, int port, String os) {
        this.IPAddr = IPAddr;
        this.USER_NAME = userName;
        this.TCP_PORT = port;
        this.OS = os;
    }
    public Client() {
        this.IPAddr = null;
        this.USER_NAME = null;
        this.TCP_PORT = 0;
        this.OS = null;
    }
    public String getUserName() {
        return USER_NAME;
    }
    public int getPort() {
        return TCP_PORT;
    }
    public String getOS() {
        return OS;
    }
    public int getUDPPort() {
        return UDP_PORT;
    }

    // 解析訊息
    public static boolean parseMessage(String message, Client client) {
        String[] parts = message.split(":");
        if (parts.length == 5) {
            client.IPAddr = parts[0];
            client.USER_NAME = parts[1];
            client.TCP_PORT = Integer.parseInt(parts[2]);
            client.UDP_PORT = Integer.parseInt(parts[3]);
            client.OS = parts[3];
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