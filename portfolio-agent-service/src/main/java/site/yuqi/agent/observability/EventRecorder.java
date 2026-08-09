package site.yuqi.agent.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.yuqi.ai.contracts.event.PlatformEvent;

import java.util.Map;

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
            String json = objectMapper.writeValueAsString(normalize(event));
            outboxRepo.insert(categorize(event.eventType()), json);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event {}: {}", event.eventType(), e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to write event {} to outbox: {}", event.eventType(), e.getMessage());
        }
    }

    private PlatformEvent normalize(PlatformEvent event) {
        OperationContext context = OperationContext.current();
        String correlationId = firstNonBlank(
                event.correlationId(),
                event.runId() == null ? null : event.runId().toString(),
                event.conversationId() == null ? null : event.conversationId().toString(),
                context.correlationId());
        String service = firstNonBlank(event.service(), "portfolio-agent-service");
        return new PlatformEvent(
                event.eventId(),
                event.eventType(),
                event.timestamp(),
                event.conversationId(),
                event.runId(),
                event.userId(),
                service,
                event.latencyMs(),
                event.status(),
                event.payload(),
                event.schemaVersion() == null ? 1 : event.schemaVersion(),
                firstNonBlank(event.traceId(), context.traceId()),
                firstNonBlank(event.spanId(), context.spanId()),
                correlationId,
                event.causationId(),
                firstNonBlank(event.idempotencyKey(), event.eventId().toString()),
                event.actor() == null ? Map.of("type", "service", "id", service) : event.actor(),
                event.subject() == null ? defaultSubject(event) : event.subject(),
                event.attempt() == null ? 1 : event.attempt());
    }

    private static Map<String, Object> defaultSubject(PlatformEvent event) {
        if (event.runId() != null) {
            return Map.of("type", "agent_run", "id", event.runId().toString());
        }
        if (event.conversationId() != null) {
            return Map.of("type", "conversation", "id", event.conversationId().toString());
        }
        return Map.of("type", "agent_event", "id", event.eventId().toString());
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
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
