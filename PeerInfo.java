import java.io.Serializable;
import java.util.UUID;

public class PeerInfo implements Serializable {
    
    private UUID nodeId;
    private String nickname;
    private String ipAddress;
    private int port;
    private long lastSeen;
    private int leaderId;
    private boolean isLeaderKey;
    private long joinTime;  // NEW: Track when peer joined
    
    public PeerInfo(UUID nodeId, String nickname, String ipAddress, int port, int leaderId) {
        this.nodeId = nodeId;
        this.nickname = nickname;
        this.ipAddress = ipAddress;
        this.port = port;
        this.leaderId = leaderId;
        this.lastSeen = System.currentTimeMillis();
        this.isLeaderKey = false;
        this.joinTime = System.currentTimeMillis();
    }
    
    // Getters
    public UUID getNodeId() { return nodeId; }
    public String getNickname() { return nickname; }
    public String getIpAddress() { return ipAddress; }
    public int getPort() { return port; }
    public long getLastSeen() { return lastSeen; }
    public int getLeaderId() { return leaderId; }
    public boolean isLeaderKey() { return isLeaderKey; }
    public long getJoinTime() { return joinTime; }
    
    // Setters
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public void setLeaderId(int leaderId) { this.leaderId = leaderId; }
    public void setLeaderKey(boolean isLeaderKey) { this.isLeaderKey = isLeaderKey; }
    public void setJoinTime(long joinTime) { this.joinTime = joinTime; }
    
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
    
    @Override
    public String toString() {
        String keyStatus = isLeaderKey ? " [KEY]" : "";
        return nickname + " (" + nodeId.toString().substring(0, 8) + "...) @ " 
               + ipAddress + ":" + port + " [Leader " + leaderId + "]" + keyStatus;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        PeerInfo other = (PeerInfo) obj;
        return nodeId.equals(other.nodeId);
    }
    
    @Override
    public int hashCode() {
        return nodeId.hashCode();
    }
}
