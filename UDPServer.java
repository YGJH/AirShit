package AirShit;
import java.net.*;
import java.util.Hashtable;

class UDPServer {

    public static void Server(Client client) {
        try {
            DatagramSocket udpSocket = new DatagramSocket(client.getUDPPort());
            byte[] recvBuf = new byte[200];
            System.out.println("UDP 服務開始，監聽端口 " + client.getUDPPort());
            System.out.println(client.getTCPPort() + ":" + client.getUDPPort());

            while (true) {
                DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
                udpSocket.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength());
                System.out.println("收到 UDP 訊息: " + message + "，來自 " + packet.getAddress());
                Client tempClient = new Client();
                if (Client.parseMessage(message, tempClient)) {
                    System.out.println("Received client ports: " 
                        + tempClient.getTCPPort() + " (TCP) : " + tempClient.getUDPPort() + " (UDP)");

                    // Compare IP and require BOTH TCP and UDP ports be equal to consider a self-message.
                    if ((tempClient.getIPAddr().equals(client.getIPAddr()) &&
                         tempClient.getTCPPort() == client.getTCPPort() &&
                         tempClient.getUDPPort() == client.getUDPPort())
                        || client.clientList.containsKey(tempClient.getUserName())) {
                        // If it's a self message or already in our list, skip processing.
                        continue;
                    }
                    System.out.println("New client detected:");
                    System.out.println("Local => IP: " + client.getIPAddr() + ", TCP: " + client.getTCPPort() 
                            + ", User: " + client.getUserName() + ", OS: " + client.getOS());
                    System.out.println("Remote => User: " + tempClient.getUserName());
                    
                    // Add tempClient to the client list.
                    client.clientList.put(tempClient.getUserName(), tempClient);
                    
                    Thread.sleep(10);
                    responseNewClient(client);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static void responseNewClient(Client client) {
        try {
            DatagramSocket udpSocket = new DatagramSocket();
            udpSocket.setBroadcast(true);
            byte[] sendData = client.getHelloMessage().getBytes();
            InetAddress clientAddress = InetAddress.getByName(client.getIPAddr());
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, clientAddress, client.getUDPPort());
            System.out.println("Responded to client at " + client.getIPAddr() + ":" + client.getUDPPort());
            udpSocket.send(sendPacket);
            udpSocket.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}