import java.net.*;
import java.util.*;

public class TestNetworkInterfaces {
    public static void main(String[] args) {
        System.out.println("=== 所有網路介面 ===");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                System.out.println("\n介面名稱: " + ni.getDisplayName());
                System.out.println("  內部名稱: " + ni.getName());
                System.out.println("  是否啟用: " + ni.isUp());
                System.out.println("  是否迴環: " + ni.isLoopback());
                System.out.println("  是否虛擬: " + ni.isVirtual());
                System.out.println("  支援多播: " + (ni.isUp() ? ni.supportsMulticast() : "N/A (not up)"));
                
                // 顯示 IP 地址
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        System.out.println("  IPv4: " + addr.getHostAddress() + 
                                         " (迴環: " + addr.isLoopbackAddress() + 
                                         ", 本地連結: " + addr.isLinkLocalAddress() + ")");
                    }
                }
                
                // 檢查是否會被過濾
                String name = ni.getDisplayName().toLowerCase();
                boolean filtered = false;
                String reason = "";
                
                if (!ni.isUp() || ni.isLoopback()) {
                    filtered = true;
                    reason = "Not up or loopback";
                } else if (!ni.supportsMulticast()) {
                    filtered = true;
                    reason = "No multicast support";
                } else if (name.contains("hyper-v") || name.contains("filter") || 
                          name.contains("vmware") || name.contains("vbox") || 
                          name.contains("virtualbox")) {
                    filtered = true;
                    reason = "Virtualization software";
                }
                
                System.out.println("  會被過濾: " + filtered + (filtered ? " (" + reason + ")" : ""));
                
                // 檢查是否有有效的 IPv4
                boolean hasValidIPv4 = false;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        hasValidIPv4 = true;
                        break;
                    }
                }
                System.out.println("  有有效IPv4: " + hasValidIPv4);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
