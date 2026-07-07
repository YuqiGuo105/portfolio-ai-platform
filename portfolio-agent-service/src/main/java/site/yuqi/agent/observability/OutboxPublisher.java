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
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Polls outbox_event table and bulk-indexes to OpenSearch daily indexes.
 * Replaces Kafka event bus (free tier 2-topic limit).
 *
 * Index naming: ai-{event_category}-YYYY.MM.DD
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(OutboxRepository.class)
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

    private String resolveIndex(String eventType) {
        String prefix = EVENT_TYPE_TO_INDEX.getOrDefault(eventType, "ai-events");
        String dateSuffix = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        return prefix + "-" + dateSuffix;
    }
}
