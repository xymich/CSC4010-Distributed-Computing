import java.util.UUID;

/**
 * Listener interface for message delivery status updates.
 * Used to notify UI components when messages are confirmed delivered.
 */
public interface MessageStatusListener {
    
    /**
     * Called when a message delivery status changes.
     * @param messageId The ID of the message
     * @param delivered true if message was acknowledged by at least one peer, false if pending
     */
    void onMessageStatusChanged(UUID messageId, boolean delivered);
}
