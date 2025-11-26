import java.io.Serializable;
import java.util.UUID;

public class RoomInfo implements Serializable {
    
    private UUID roomId;
    private String roomName;
    private String seedNodeAddress;
    private int seedNodePort;
    private int memberCount;
    private long lastSeen;
    
    public RoomInfo(UUID roomId, String roomName, String seedNodeAddress, int seedNodePort, int memberCount) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.seedNodeAddress = seedNodeAddress;
        this.seedNodePort = seedNodePort;
        this.memberCount = memberCount;
        this.lastSeen = System.currentTimeMillis();
    }
    
    // Getters
    public UUID getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public String getSeedNodeAddress() { return seedNodeAddress; }
    public int getSeedNodePort() { return seedNodePort; }
    public int getMemberCount() { return memberCount; }
    public long getLastSeen() { return lastSeen; }
    
    // Setters
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
    public void updateLastSeen() { this.lastSeen = System.currentTimeMillis(); }
    
    @Override
    public String toString() {
        return "Room: \"" + roomName + "\" (" + memberCount + " members) @ " 
               + seedNodeAddress + ":" + seedNodePort;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        RoomInfo other = (RoomInfo) obj;
        return roomId.equals(other.roomId);
    }
    
    @Override
    public int hashCode() {
        return roomId.hashCode();
    }
}
