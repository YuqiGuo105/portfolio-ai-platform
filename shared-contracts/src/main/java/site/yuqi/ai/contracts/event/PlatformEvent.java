package site.yuqi.ai.contracts.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Unified event envelope — all events written to outbox → OpenSearch use this structure.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformEvent(
        UUID eventId,
        String eventType,
        Instant timestamp,
        UUID conversationId,
        UUID runId,
        String userId,
        String service,
        Integer latencyMs,
        String status,
        Map<String, Object> payload
) {
    public static PlatformEvent.PlatformEventBuilder now(String eventType) {
        return PlatformEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .timestamp(Instant.now());
    }
}
