import java.io.Serializable;
import java.util.UUID;

public class NetworkPacket implements Serializable {
    
    private MessageType type;
    private UUID senderId;
    private String senderNickname;
    private long lamportTimestamp;
    private Object payload;
    
    // Fragmentation fields
    private boolean isFragmented;
    private UUID fragmentGroupId;
    private int fragmentIndex;
    private int totalFragments;
    
    // Basic constructor
    public NetworkPacket(MessageType type, UUID senderId, String senderNickname, 
                        long lamportTimestamp, Object payload) {
        this.type = type;
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.lamportTimestamp = lamportTimestamp;
        this.payload = payload;
        this.isFragmented = false;
    }
    
    // Constructor for fragmented packets
    public NetworkPacket(MessageType type, UUID senderId, String senderNickname,
                        long lamportTimestamp, Object payload,
                        UUID fragmentGroupId, int fragmentIndex, int totalFragments) {
        this(type, senderId, senderNickname, lamportTimestamp, payload);
        this.isFragmented = true;
        this.fragmentGroupId = fragmentGroupId;
        this.fragmentIndex = fragmentIndex;
        this.totalFragments = totalFragments;
    }
    
    // Getters
    public MessageType getType() { return type; }
    public UUID getSenderId() { return senderId; }
    public String getSenderNickname() { return senderNickname; }
    public long getLamportTimestamp() { return lamportTimestamp; }
    public Object getPayload() { return payload; }
    public boolean isFragmented() { return isFragmented; }
    public UUID getFragmentGroupId() { return fragmentGroupId; }
    public int getFragmentIndex() { return fragmentIndex; }
    public int getTotalFragments() { return totalFragments; }
    
    @Override
    public String toString() {
        String fragInfo = isFragmented ? 
            " [Fragment " + fragmentIndex + "/" + totalFragments + "]" : "";
        return "Packet[" + type + " from " + senderNickname + 
               " @" + lamportTimestamp + fragInfo + "]";
    }
}
