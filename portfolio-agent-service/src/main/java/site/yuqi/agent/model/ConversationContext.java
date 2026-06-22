package site.yuqi.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of the live conversation, scoped to a single
 * {@code /api/chat} call. Holds the planner's working set: recent turns,
 * page context, the user's identity (if known) and the tool invocations
 * accumulated so far in this turn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationContext {

    private String sessionId;
    private String userEmail;
    private List<ChatRequest.Message> messages;
    private Map<String, Object> pageContext;

    @Builder.Default
    private List<ToolInvocation> invokedTools = new ArrayList<>();

    /** Resolved provider id (legacy field — no longer used by the intent pipeline). */
    private String activeProvider;
}
