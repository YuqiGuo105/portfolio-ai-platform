package site.yuqi.agent.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMemory {
    private String conversationId;

    @Builder.Default
    private Map<String, Object> compactSummary = new LinkedHashMap<>();

    @Builder.Default
    private Map<String, Object> structuredState = new LinkedHashMap<>();

    @Builder.Default
    private List<MemoryTurn> turns = new ArrayList<>();

    private Map<String, Object> pendingAction;
    private Instant createdAt;
    private Instant updatedAt;
    private long totalTurns;

    public static ConversationMemory empty(String conversationId) {
        Instant now = Instant.now();
        return ConversationMemory.builder()
                .conversationId(conversationId)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
