package site.yuqi.agent.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.cat.IndicesResponse;
import org.opensearch.client.opensearch.cat.indices.IndicesRecord;
import org.opensearch.client.opensearch.indices.DeleteIndexRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;

/**
 * Automatically deletes old ai-* daily indexes to stay within the free-tier
 * shard budget. Runs once per hour.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexRetentionService {

    private final OpenSearchClient openSearchClient;

    @Value("${observability.retention.days:7}")
    private int retentionDays;

    @Value("${observability.retention.max-shards:18}")
    private int maxShards;

    private static final String AI_INDEX_PREFIX = "ai-";
    private static final DateTimeFormatter DATE_SUFFIX = DateTimeFormatter.ISO_LOCAL_DATE;

    @Scheduled(fixedDelayString = "${observability.retention.check-interval-ms:3600000}",
               initialDelayString = "${observability.retention.initial-delay-ms:60000}")
    public void cleanExpiredIndexes() {
        try {
            IndicesResponse catResp = openSearchClient.cat().indices(b -> b.index(AI_INDEX_PREFIX + "*"));
            List<IndicesRecord> aiIndexes = catResp.valueBody();
            if (aiIndexes.isEmpty()) return;

            LocalDate cutoff = LocalDate.now().minusDays(retentionDays);
            int deleted = 0;

            for (IndicesRecord idx : aiIndexes) {
                String name = idx.index();
                if (name == null) continue;
                LocalDate indexDate = extractDate(name);
                if (indexDate != null && indexDate.isBefore(cutoff)) {
                    deleteIndex(name);
                    deleted++;
                }
            }

            if (deleted > 0) {
                log.info("Retention cleanup: deleted {} expired ai-* indexes (cutoff={})", deleted, cutoff);
            }

            // Emergency: if we still exceed shard budget, remove oldest first
            enforceShardBudget();
        } catch (Exception e) {
            log.warn("Index retention check failed: {}", e.getMessage());
        }
    }

    private void enforceShardBudget() {
        try {
            IndicesResponse catResp = openSearchClient.cat().indices(b -> b.index(AI_INDEX_PREFIX + "*"));
            List<IndicesRecord> remaining = catResp.valueBody();
            int totalShards = remaining.stream()
                    .mapToInt(idx -> parseIntSafe(idx.pri()))
                    .sum();

            if (totalShards <= maxShards) return;

            // Sort by date ascending, delete oldest until under budget
            List<IndicesRecord> sorted = remaining.stream()
                    .sorted(Comparator.comparing(idx -> extractDateOrMax(idx.index())))
                    .toList();

            for (IndicesRecord idx : sorted) {
                if (totalShards <= maxShards) break;
                String name = idx.index();
                if (name == null) continue;
                int shards = parseIntSafe(idx.pri());
                deleteIndex(name);
                totalShards -= shards;
                log.info("Shard-budget cleanup: removed {} (remaining shards={})", name, totalShards);
            }
        } catch (Exception e) {
            log.warn("Shard budget enforcement failed: {}", e.getMessage());
        }
    }

    private void deleteIndex(String name) {
        try {
            openSearchClient.indices().delete(new DeleteIndexRequest.Builder().index(name).build());
        } catch (Exception e) {
            log.warn("Failed to delete index {}: {}", name, e.getMessage());
        }
    }

    private LocalDate extractDate(String indexName) {
        // Pattern: ai-{category}-YYYY-MM-DD
        int lastDash = indexName.lastIndexOf('-');
        if (lastDash < 8) return null;
        int secondDash = indexName.lastIndexOf('-', lastDash - 1);
        if (secondDash < 4) return null;
        int thirdDash = indexName.lastIndexOf('-', secondDash - 1);
        if (thirdDash < 0) return null;
        String dateStr = indexName.substring(thirdDash + 1);
        try {
            return LocalDate.parse(dateStr, DATE_SUFFIX);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LocalDate extractDateOrMax(String indexName) {
        LocalDate d = extractDate(indexName);
        return d != null ? d : LocalDate.MAX;
    }

    private int parseIntSafe(String s) {
        if (s == null || s.isBlank()) return 1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 1; }
    }
}
