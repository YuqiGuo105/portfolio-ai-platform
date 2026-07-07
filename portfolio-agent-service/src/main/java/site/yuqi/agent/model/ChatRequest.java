package site.yuqi.agent.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Inbound chat request from ChatWidget. {@code sessionId} survives across
 * turns; {@code conversationId} is server-derived and used only for Redis
 * hot memory. {@code messages} carries the client-side conversation tail for
 * backward compatibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @NotBlank
    private String sessionId;

    private String conversationId;

    private String userEmail;

    /**
     * Comma-separated permission roles ({@code VIEWER,EDITOR,PUBLISHER,ADMIN}).
     * <strong>Ignored</strong> for JWT-authenticated callers — the filter
     * overwrites this with server-derived roles before the controller runs.
     * Only meaningful when the caller is the internal Portfolio proxy.
     */
    private String userRoles;

    @NotNull
    @Size(min = 1, max = 50)
    private List<Message> messages;

    private Map<String, Object> pageContext;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        /** {@code user} | {@code assistant} | {@code system}. */
        @NotBlank
        private String role;

        @NotBlank
        @Size(max = 8_000)
        private String content;
    }
}
