import java.io.Serializable;

public enum MessageType implements Serializable {
    CHAT_MESSAGE,       // Regular chat message
    PEER_DISCOVERY,     // Announcing presence to network
    PEER_LIST,          // Sharing known peers
    HEARTBEAT,          // Keep-alive signal
    MESSAGE_SYNC,       // Request missing messages
    JOIN_REQUEST,       // New node joining
    LEAVE_NOTIFY,       // Graceful disconnect
    FRAGMENT,           // Fragment of larger message
    FRAGMENT_ACK        // Acknowledgment of fragment
}