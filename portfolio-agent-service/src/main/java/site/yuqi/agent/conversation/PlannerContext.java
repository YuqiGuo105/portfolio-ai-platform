package site.yuqi.agent.conversation;

import java.util.List;
import java.util.Map;

public record PlannerContext(
        Map<String, Object> compactSummary,
        Map<String, Object> structuredState,
        List<Map<String, String>> recentMessages,
        Map<String, Object> pendingAction
) {
    public static PlannerContext empty(List<Map<String, String>> recentMessages) {
        return new PlannerContext(Map.of(), Map.of(),
                recentMessages == null ? List.of() : recentMessages,
                null);
    }
}
