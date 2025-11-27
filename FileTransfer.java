import java.io.Serializable;
import java.util.UUID;

public class FileTransfer implements Serializable {

    private final UUID fileId;
    private final String fileName;
    private final long fileSize;
    private final byte[] data;
    private final String senderNickname;

    public FileTransfer(UUID fileId, String fileName, long fileSize, byte[] data, String senderNickname) {
        this.fileId = fileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.data = data;
        this.senderNickname = senderNickname;
    }

    public UUID getFileId() {
        return fileId;
    }

    public String getFileName() {
        return fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public byte[] getData() {
        return data;
    }

    public String getSenderNickname() {
        return senderNickname;
    }
}
