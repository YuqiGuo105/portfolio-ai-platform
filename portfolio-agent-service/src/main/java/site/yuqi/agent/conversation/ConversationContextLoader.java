package site.yuqi.agent.conversation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConversationContextLoader {

    private final RedisConversationStore redisStore;
    private final MemorySanitizer sanitizer;

    public PlannerContext load(String conversationId, List<Map<String, String>> ignoredClientRecentMessages) {
        if (conversationId == null || conversationId.isBlank()) {
            return PlannerContext.empty(List.of());
        }
        ConversationMemory memory = redisStore.load(conversationId);
        List<Map<String, String>> merged = new ArrayList<>();
        if (memory.getTurns() != null) {
            int start = Math.max(0, memory.getTurns().size() - 6);
            for (int i = start; i < memory.getTurns().size(); i++) {
                MemoryTurn turn = memory.getTurns().get(i);
                if (turn.getContent() == null || turn.getContent().isBlank()) continue;
                merged.add(Map.of(
                        "role", nonBlank(turn.getRole(), "user"),
                        "content", sanitizer.truncate(turn.getContent(), 800)));
            }
        }
        return new PlannerContext(
                safeMap(memory.getCompactSummary()),
                safeMap(memory.getStructuredState()),
                lastSix(merged),
                memory.getPendingAction());
    }

    private List<Map<String, String>> lastSix(List<Map<String, String>> turns) {
        if (turns == null || turns.isEmpty()) return List.of();
        int start = Math.max(0, turns.size() - 6);
        List<Map<String, String>> out = new ArrayList<>();
        for (int i = start; i < turns.size(); i++) {
            Map<String, String> turn = turns.get(i);
            if (turn == null) continue;
            String content = turn.get("content");
            if (content == null || content.isBlank()) continue;
            out.add(Map.of(
                    "role", nonBlank(turn.get("role"), "user"),
                    "content", sanitizer.truncate(content, 800)));
        }
        return out;
    }

    private Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
