package site.yuqi.agent.observability;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Polls outbox_event table and bulk-indexes to OpenSearch daily indexes.
 * Index naming: ai-{event_category}-YYYY.MM.DD
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepo;
    private final OpenSearchClient openSearchClient;
    private final ObjectMapper objectMapper;

    @Value("${observability.outbox.batch-size:100}")
    private int batchSize;

    private static final Map<String, String> EVENT_TYPE_TO_INDEX = Map.of(
            "agent_run", "ai-agent-runs",
            "agent_step", "ai-agent-steps",
            "model_call", "ai-model-calls",
            "tool_call", "ai-tool-calls",
            "retrieval", "ai-retrieval",
            "safety", "ai-safety",
            "handoff", "ai-handoff",
            "answer", "ai-answers",
            "feedback", "ai-feedback"
    );

    @Scheduled(fixedDelayString = "${observability.outbox.poll-interval-ms:5000}")
    public void publishPendingEvents() {
        List<OutboxRepository.OutboxEvent> pending = outboxRepo.findPendingBatch(batchSize);
        if (pending.isEmpty()) return;

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (OutboxRepository.OutboxEvent e : pending) {
                String index = resolveIndex(e.eventType());
                Map<String, Object> doc = objectMapper.readValue(
                        e.payloadJson(), new TypeReference<Map<String, Object>>() {});
                bulkBuilder.operations(op -> op.index(
                        IndexOperation.of(idx -> idx
                                .index(index)
                                .id(e.id().toString())
                                .document(doc))));
                bulkBuilder.operations(op -> op.index(
                        IndexOperation.of(idx -> idx
                                .index(operationIndex(doc))
                                .id(String.valueOf(doc.getOrDefault("eventId", e.id())))
                                .document(operationDocument(doc)))));
            }

            BulkResponse response = openSearchClient.bulk(bulkBuilder.build());
            if (!response.errors()) {
                outboxRepo.markPublished(pending.stream().map(OutboxRepository.OutboxEvent::id).toList());
                log.debug("Published {} events to OpenSearch", pending.size());
            } else {
                log.warn("Bulk index had errors, marking failed with retry for {} events", pending.size());
                outboxRepo.markFailedWithRetry(pending.stream().map(OutboxRepository.OutboxEvent::id).toList());
            }
        } catch (Exception ex) {
            log.error("Outbox publish failed: {}", ex.getMessage());
            outboxRepo.markFailedWithRetry(pending.stream().map(OutboxRepository.OutboxEvent::id).toList());
        }
    }

    private String operationIndex(Map<String, Object> doc) {
        Object timestamp = doc.get("timestamp");
        LocalDate date = LocalDate.now();
        if (timestamp != null) {
            try {
                date = Instant.parse(String.valueOf(timestamp)).atZone(java.time.ZoneOffset.UTC).toLocalDate();
            } catch (RuntimeException ignored) {
                // Current date keeps legacy events queryable if their timestamp is malformed.
            }
        }
        return "platform-operation-events-" + date.format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    private Map<String, Object> operationDocument(Map<String, Object> doc) {
        Map<String, Object> payload = doc.get("payload") instanceof Map<?, ?> raw
                ? sanitizeAttributes(raw)
                : Map.of();
        Map<String, Object> operation = new java.util.LinkedHashMap<>();
        putIfPresent(operation, "eventId", doc.get("eventId"));
        putIfPresent(operation, "eventType", doc.get("eventType"));
        operation.put("schemaVersion", doc.getOrDefault("schemaVersion", 1));
        putIfPresent(operation, "occurredAt", doc.get("timestamp"));
        putIfPresent(operation, "traceId", doc.get("traceId"));
        putIfPresent(operation, "spanId", doc.get("spanId"));
        putIfPresent(operation, "runId", doc.get("runId"));
        putIfPresent(operation, "correlationId", doc.get("correlationId"));
        putIfPresent(operation, "causationId", doc.get("causationId"));
        putIfPresent(operation, "idempotencyKey", doc.get("idempotencyKey"));
        putIfPresent(operation, "actor", doc.get("actor"));
        putIfPresent(operation, "subject", doc.get("subject"));
        putIfPresent(operation, "sourceService", doc.get("service"));
        putIfPresent(operation, "status", doc.get("status"));
        operation.put("attempt", doc.getOrDefault("attempt", 1));
        putIfPresent(operation, "durationMs", doc.get("latencyMs"));
        operation.put("attributes", payload);
        return operation;
    }

    private Map<String, Object> sanitizeAttributes(Map<?, ?> payload) {
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
        java.util.Set<String> allowed = java.util.Set.of(
                "route", "topic", "toolName", "model", "provider", "operation",
                "zeroHit", "returnedChunks", "retrievalStrategy", "verdict", "category",
                "promptVersion", "generationTier", "requiresConfirmation", "riskLevel");
        payload.forEach((key, value) -> {
            String field = String.valueOf(key);
            if (allowed.contains(field) && isScalar(value)) sanitized.put(field, value);
        });
        return sanitized;
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) target.put(key, value);
    }

    private String resolveIndex(String eventType) {
        String prefix = EVENT_TYPE_TO_INDEX.getOrDefault(eventType, "ai-events");
        String dateSuffix = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return prefix + "-" + dateSuffix;
    }
}
