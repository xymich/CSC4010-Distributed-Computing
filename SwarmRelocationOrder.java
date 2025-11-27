import java.io.Serializable;
import java.util.UUID;

public class SwarmRelocationOrder implements Serializable {
    private final int sourceSwarmId;
    private final String targetIp;
    private final int targetPort;
    private final UUID targetRoomId;
    private final String targetRoomName;
    private final int targetSwarmId;

    public SwarmRelocationOrder(int sourceSwarmId, String targetIp, int targetPort,
                                UUID targetRoomId, String targetRoomName, int targetSwarmId) {
        this.sourceSwarmId = sourceSwarmId;
        this.targetIp = targetIp;
        this.targetPort = targetPort;
        this.targetRoomId = targetRoomId;
        this.targetRoomName = targetRoomName;
        this.targetSwarmId = targetSwarmId;
    }

    public int getSourceSwarmId() {
        return sourceSwarmId;
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

    public int getTargetSwarmId() {
        return targetSwarmId;
    }
}
