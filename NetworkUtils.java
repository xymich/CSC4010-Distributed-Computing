import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public final class NetworkUtils {

    private NetworkUtils() {}

    public static String detectBestAddress() {
        String envOverride = System.getenv("CHAT_ADVERTISE_IP");
        if (isValidIPv4(envOverride)) {
            return envOverride.trim();
        }

        String autoDetected = findNonLoopbackAddress();
        if (autoDetected != null) {
            return autoDetected;
        }

        return "127.0.0.1";
    }

    private static String findNonLoopbackAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();

                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address inet4) {
                        if (!inet4.isLoopbackAddress() && !inet4.isLinkLocalAddress()) {
                            return inet4.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            // Ignore and fall through to other strategies
        }

        try {
            InetAddress localhost = InetAddress.getLocalHost();
            if (localhost instanceof Inet4Address inet4) {
                if (!inet4.isLoopbackAddress()) {
                    return inet4.getHostAddress();
                }
            }
        } catch (Exception e) {
            // Ignore, fallback to loopback below
        }

        return null;
    }

    public static boolean isValidIPv4(String ip) {
        if (ip == null) {
            return false;
        }

        String trimmed = ip.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        if (trimmed.contains(" ")) {
            return false;
        }

        String[] parts = trimmed.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        try {
            for (String part : parts) {
                if (part.isEmpty()) {
                    return false;
                }
                if (part.length() > 1 && part.startsWith("0")) {
                    // Allow leading zeros but avoid non-numeric characters
                }
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
