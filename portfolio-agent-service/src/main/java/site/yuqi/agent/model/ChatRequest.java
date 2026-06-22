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
 * turns; {@code messages} carries the conversation tail (recent N turns only —
 * the agent does not store conversation history in Sprint 1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @NotBlank
    private String sessionId;

    private String userEmail;

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
