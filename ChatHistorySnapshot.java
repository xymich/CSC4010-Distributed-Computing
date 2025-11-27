import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatHistorySnapshot implements Serializable {

    private final List<ChatMessage> messages;
    private final long lamportCursor;

    public ChatHistorySnapshot(List<ChatMessage> messages, long lamportCursor) {
        if (messages == null) {
            this.messages = new ArrayList<>();
        } else {
            this.messages = new ArrayList<>(messages);
        }
        this.lamportCursor = lamportCursor;
    }

    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    public long getLamportCursor() {
        return lamportCursor;
    }
}
