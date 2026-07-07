package site.yuqi.agent.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.yuqi.ai.contracts.event.PlatformEvent;

/**
 * Convenience wrapper — writes a PlatformEvent to the outbox table in the same transaction.
 * Downstream OutboxPublisher polls and syncs to OpenSearch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventRecorder {

    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public void record(PlatformEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            outboxRepo.insert(categorize(event.eventType()), json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event {}: {}", event.eventType(), e.getMessage());
        }
    }

    /**
     * Maps full event type to category for index routing.
     * e.g. "agent_run.started" -> "agent_run", "model_call.completed" -> "model_call"
     */
    private String categorize(String eventType) {
        int dot = eventType.indexOf('.');
        return dot > 0 ? eventType.substring(0, dot) : eventType;
    }
}
