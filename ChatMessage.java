import java.io.Serializable;
import java.util.UUID;

public class ChatMessage implements Serializable {
    
    private UUID messageId;
    private UUID senderId;
    private String senderNickname;
    private String content;
    private long lamportTimestamp;
    private long systemTimestamp;
    
    public ChatMessage(UUID senderId, String senderNickname, String content, long lamportTimestamp) {
        this.messageId = UUID.randomUUID();
        this.senderId = senderId;
        this.senderNickname = senderNickname;
        this.content = content;
        this.lamportTimestamp = lamportTimestamp;
        this.systemTimestamp = System.currentTimeMillis();
    }
    
    // Getters
    public UUID getMessageId() { return messageId; }
    public UUID getSenderId() { return senderId; }
    public String getSenderNickname() { return senderNickname; }
    public String getContent() { return content; }
    public long getLamportTimestamp() { return lamportTimestamp; }
    public long getSystemTimestamp() { return systemTimestamp; }
    
    @Override
    public String toString() {
        return "[" + lamportTimestamp + "] " + senderNickname + ": " + content;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ChatMessage other = (ChatMessage) obj;
        return messageId.equals(other.messageId);
    }
    
    @Override
    public int hashCode() {
        return messageId.hashCode();
    }
}