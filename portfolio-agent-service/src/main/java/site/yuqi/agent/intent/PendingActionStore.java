package site.yuqi.agent.intent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pending-action store with TTL eviction. Adequate for single-pod
 * Cloud Run usage in Sprint 1 (the agent service runs with min/max instances
 * 0..1). For multi-instance scale-out, swap in a Redis-backed implementation
 * — same interface, no callers change.
 */
@Slf4j
@Component
public class PendingActionStore {

    private final ConcurrentHashMap<String, PendingAction> store = new ConcurrentHashMap<>();

    @Value("${agent.intent.pending-action.ttl-seconds:300}")
    private long ttlSeconds;

    public PendingAction stage(
            String sessionId,
            String userId,
            ToolDefinition tool,
            java.util.Map<String, Object> resolvedArgs,
            String previewMessage) {
        Instant now = Instant.now();
        PendingAction action = PendingAction.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .userId(userId)
                .toolName(tool.name())
                .intent(tool.intent())
                .riskLevel(tool.riskLevel())
                .resolvedArguments(resolvedArgs)
                .previewMessage(previewMessage)
                .createdAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .build();
        store.put(action.getId(), action);
        sweepExpired();
        return action;
    }

    public Optional<PendingAction> consume(String id, String sessionId) {
        sweepExpired();
        PendingAction a = store.remove(id);
        if (a == null) return Optional.empty();
        if (sessionId != null && !sessionId.equals(a.getSessionId())) {
            log.warn("PendingAction {} session mismatch; rejecting", id);
            return Optional.empty();
        }
        if (Instant.now().isAfter(a.getExpiresAt())) {
            return Optional.empty();
        }
        return Optional.of(a);
    }

    private void sweepExpired() {
        Instant now = Instant.now();
        store.entrySet().removeIf(e -> now.isAfter(e.getValue().getExpiresAt()));
    }
}
