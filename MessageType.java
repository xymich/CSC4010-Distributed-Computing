import java.io.Serializable;

public enum MessageType implements Serializable {
    CHAT_MESSAGE,       // Regular chat message
    PEER_DISCOVERY,     // Announcing presence to network
    PEER_LIST,          // Sharing known peers
    PEER_LIST_REQUEST,  // Requesting an updated peer list
    HEARTBEAT,          // Keep-alive signal
    MESSAGE_SYNC,       // Request missing messages
    HISTORY_SNAPSHOT,   // Bulk history payload response
    JOIN_REQUEST,       // New node joining
    LEAVE_NOTIFY,       // Graceful disconnect
    FRAGMENT,           // Fragment of larger message
    FRAGMENT_ACK,       // Acknowledgment of fragment
    KEY_NODE_ANNOUNCE,  // Key node announcing itself
    KEY_NODE_LIST,      // List of key nodes from other swarms
    KEY_NODE_REQUEST,   // Request for key node list
    FILE_TRANSFER,      // Broadcast file data
    SWARM_RELOCATE      // Key node instructs swarm to follow
}