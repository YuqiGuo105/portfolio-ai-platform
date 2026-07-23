package site.yuqi.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request body from the ChatWidget (POST /api/rag/answer/stream).
 * Matches the exact JSON shape the frontend sends.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentStreamRequest {

    private String question;
    private String sessionId;
    private String conversationId;
    private String mode;          // "FAST" or "DEEPTHINKING"
    private String scopeMode;     // "OWNER_ONLY" or "GENERAL"
    /** Opaque, backend-issued attachment references. Raw/public URLs are not accepted. */
    private List<AttachmentReference> attachments;
    /** Legacy field retained for wire compatibility; the backend intentionally ignores it. */
    private List<String> fileUrls;
    private String userId;
    private String userEmail;
    private String userRoles;
    private String pendingActionId;
    private Boolean confirm;
    private List<ConversationTurn> conversationHistory;
    private Map<String, Object> ext;   // { currentPageUrl, currentPagePattern, pageContextText, pageTitle }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ConversationTurn {
        private String role;
        private String content;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AttachmentReference {
        private String id;
        private String name;
        private String mimeType;
        private Long sizeBytes;
    }
}
