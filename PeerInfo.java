import java.io.Serializable;
import java.util.UUID;

public class PeerInfo implements Serializable {
    
    private UUID nodeId;
    private String nickname;
    private String ipAddress;
    private int port;
    private long lastSeen;
    private int swarmId;
    private boolean isSwarmKey;
    
    public PeerInfo(UUID nodeId, String nickname, String ipAddress, int port, int swarmId) {
        this.nodeId = nodeId;
        this.nickname = nickname;
        this.ipAddress = ipAddress;
        this.port = port;
        this.swarmId = swarmId;
        this.lastSeen = System.currentTimeMillis();
        this.isSwarmKey = false;
    }
    
    // Getters
    public UUID getNodeId() { return nodeId; }
    public String getNickname() { return nickname; }
    public String getIpAddress() { return ipAddress; }
    public int getPort() { return port; }
    public long getLastSeen() { return lastSeen; }
    public int getSwarmId() { return swarmId; }
    public boolean isSwarmKey() { return isSwarmKey; }
    
    // Setters
    public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    public void setSwarmId(int swarmId) { this.swarmId = swarmId; }
    public void setSwarmKey(boolean isSwarmKey) { this.isSwarmKey = isSwarmKey; }
    
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
    
    @Override
    public String toString() {
        String keyStatus = isSwarmKey ? " [KEY]" : "";
        return nickname + " (" + nodeId.toString().substring(0, 8) + "...) @ " 
               + ipAddress + ":" + port + " [Swarm " + swarmId + "]" + keyStatus;
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
