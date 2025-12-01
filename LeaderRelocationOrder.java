import java.io.Serializable;
import java.util.UUID;

public class LeaderRelocationOrder implements Serializable {
    private final int sourceLeaderId;
    private final String targetIp;
    private final int targetPort;
    private final UUID targetRoomId;
    private final String targetRoomName;
    private final int targetLeaderId;

    public LeaderRelocationOrder(int sourceLeaderId, String targetIp, int targetPort,
                                UUID targetRoomId, String targetRoomName, int targetLeaderId) {
        this.sourceLeaderId = sourceLeaderId;
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.targetRoomId = targetRoomId;
        this.targetRoomName = targetRoomName;
        this.targetLeaderId = targetLeaderId;
    }

    public int getSourceLeaderId() {
        return sourceLeaderId;
    }

    public String getTargetIp() {
        return targetIp;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public UUID getTargetRoomId() {
        return targetRoomId;
    }

    public String getTargetRoomName() {
        return targetRoomName;
    }

    public int getTargetLeaderId() {
        return targetLeaderId;
    }
}
