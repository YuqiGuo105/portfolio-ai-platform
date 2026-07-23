package site.yuqi.agent.attachment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentRecord {

    public enum Status {
        ISSUED,
        READY,
        PARSED,
        DELETE_RETRY
    }

    private String id;
    private String conversationId;
    private String bucket;
    private String objectPath;
    private String originalName;
    private String mimeType;
    private long declaredSizeBytes;
    private long storedSizeBytes;
    private Status status;
    private String parsedContext;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant expiresAt;
    private int deleteAttempts;
    private String lastDeleteError;
}
