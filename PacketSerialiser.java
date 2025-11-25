import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PacketSerialiser {
    
    // Maximum safe UDP packet size (leaving room for headers)
    public static final int MAX_PACKET_SIZE = 8192; // 8KB
    private static final int HEADER_OVERHEAD = 512; // Estimate for serialization overhead
    public static final int MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - HEADER_OVERHEAD;
    
    /**
     * Serialize a NetworkPacket to bytes for UDP transmission
     */
    public static byte[] serialize(NetworkPacket packet) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(packet);
        oos.flush();
        return baos.toByteArray();
    }
    
    /**
     * Deserialize bytes back to NetworkPacket
     */
    public static NetworkPacket deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        return (NetworkPacket) ois.readObject();
    }
    
    /**
     * Fragment a large packet into multiple smaller packets
     */
    public static List<NetworkPacket> fragment(NetworkPacket packet) throws IOException {
        List<NetworkPacket> fragments = new ArrayList<>();
        
        // Serialize the payload to check size
        byte[] payloadBytes = serializePayload(packet.getPayload());
        
        // If small enough, no fragmentation needed
        if (payloadBytes.length <= MAX_PAYLOAD_SIZE) {
            fragments.add(packet);
            return fragments;
        }
        
        // Fragment the payload
        UUID fragmentGroupId = UUID.randomUUID();
        int totalFragments = (int) Math.ceil((double) payloadBytes.length / MAX_PAYLOAD_SIZE);
        
        for (int i = 0; i < totalFragments; i++) {
            int start = i * MAX_PAYLOAD_SIZE;
            int end = Math.min(start + MAX_PAYLOAD_SIZE, payloadBytes.length);
            byte[] fragmentData = new byte[end - start];
            System.arraycopy(payloadBytes, start, fragmentData, 0, fragmentData.length);
            
            NetworkPacket fragment = new NetworkPacket(
                MessageType.FRAGMENT,
                packet.getSenderId(),
                packet.getSenderNickname(),
                packet.getLamportTimestamp(),
                new FragmentPayload(packet.getType(), fragmentData),
                fragmentGroupId,
                i,
                totalFragments
            );
            
            fragments.add(fragment);
        }
        
        return fragments;
    }
    
    /**
     * Helper to serialize just the payload
     */
    private static byte[] serializePayload(Object payload) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(payload);
        oos.flush();
        return baos.toByteArray();
    }
    
    /**
     * Inner class to hold fragment data
     */
    public static class FragmentPayload implements Serializable {
        public MessageType originalType;
        public byte[] data;
        
        public FragmentPayload(MessageType originalType, byte[] data) {
            this.originalType = originalType;
            this.data = data;
        }
    }
}
