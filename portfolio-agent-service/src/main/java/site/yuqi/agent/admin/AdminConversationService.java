package site.yuqi.agent.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminConversationService {

    private static final Set<String> UNSUCCESSFUL_TERMINAL_STATUSES = Set.of(
            "blocked",
            "failed",
            "budget_exhausted",
            "forbidden",
            "unauthorized",
            "rate_limited",
            "cancelled");
    private static final int DEFAULT_HOURS = 24 * 7;
    private static final int MAX_HOURS = 24 * 30;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final AdminConversationEventRepository repository;
    private final ObjectMapper objectMapper;

    public ConversationResponse list(String query, int requestedHours, int requestedLimit) {
        int hours = clamp(requestedHours, 1, MAX_HOURS, DEFAULT_HOURS);
        int limit = clamp(requestedLimit, 1, MAX_LIMIT, DEFAULT_LIMIT);
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<AdminConversationEventRepository.EventRow> rows =
                repository.findRunEvents(since, query, limit);
        List<ConversationRun> items = aggregate(rows);
        return new ConversationResponse(items, summarize(items), items.size(), hours);
    }

    List<ConversationRun> aggregate(List<AdminConversationEventRepository.EventRow> rows) {
        Map<String, RunAccumulator> runs = new LinkedHashMap<>();
        for (AdminConversationEventRepository.EventRow row : rows) {
            try {
                JsonNode envelope = objectMapper.readTree(row.payloadJson());
                String runId = text(envelope, "runId");
                if (runId == null) continue;

                RunAccumulator run = runs.computeIfAbsent(runId, RunAccumulator::new);
                String eventType = valueOr(text(envelope, "eventType"), row.category());
                Instant timestamp = instantOr(text(envelope, "timestamp"), row.createdAt());
                String status = text(envelope, "status");
                Integer latencyMs = integer(envelope, "latencyMs");
                JsonNode payload = envelope.path("payload");

                run.lastEventAt = timestamp;
                if ("agent_run.started".equals(eventType)) {
                    run.startedAt = timestamp;
                    run.status = valueOr(status, "running");
                    run.question = text(payload, "question");
                    run.sessionId = text(payload, "sessionId");
                    run.conversationId = text(payload, "conversationId");
                } else if ("agent_run.completed".equals(eventType)) {
                    String finalStatus = text(payload, "finalStatus");
                    run.completedAt = timestamp;
                    run.status = valueOr(finalStatus, valueOr(status, run.status));
                    run.route = valueOr(run.route, routeFromFinalStatus(finalStatus));
                    run.latencyMs = latencyMs != null ? latencyMs : run.latencyMs;
                } else if (eventType.startsWith("answer.")) {
                    run.answer = valueOr(text(payload, "answer"), run.answer);
                    run.route = valueOr(text(payload, "route"), run.route);
                    run.sessionId = valueOr(text(payload, "sessionId"), run.sessionId);
                    run.conversationId = valueOr(text(payload, "conversationId"), run.conversationId);
                    run.status = valueOr(status, run.status);
                    run.latencyMs = latencyMs != null ? latencyMs : run.latencyMs;
                } else {
                    run.steps.add(new ConversationStep(
                            eventType,
                            timestamp,
                            latencyMs,
                            status,
                            detail(payload)));
                }
            } catch (Exception exception) {
                log.warn("Skipping malformed conversation event id={}: {}", row.id(), exception.getMessage());
            }
        }

        return runs.values().stream()
                .map(RunAccumulator::toView)
                .filter(run -> run.question() != null || run.answer() != null)
                .toList();
    }

    private Summary summarize(List<ConversationRun> items) {
        long completed = items.stream().filter(this::isCompleted).count();
        long blocked = items.stream().filter(run -> "blocked".equalsIgnoreCase(run.status())).count();
        List<Integer> latencies = items.stream()
                .filter(this::isCompleted)
                .map(ConversationRun::latencyMs)
                .filter(value -> value != null && value >= 0)
                .toList();
        Integer averageLatencyMs = latencies.isEmpty()
                ? null
                : (int) Math.round(latencies.stream().mapToInt(Integer::intValue).average().orElse(0));
        return new Summary(items.size(), completed, blocked, averageLatencyMs);
    }

    private boolean isCompleted(ConversationRun run) {
        if (run.completedAt() == null) return false;
        String status = valueOr(run.status(), "").toLowerCase(Locale.ROOT);
        return !UNSUCCESSFUL_TERMINAL_STATUSES.contains(status);
    }

    private Map<String, Object> detail(JsonNode payload) {
        if (payload == null || !payload.isObject()) return Map.of();
        return objectMapper.convertValue(payload, new TypeReference<>() { });
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value <= 0) return fallback;
        return Math.min(Math.max(value, min), max);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) return null;
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer integer(JsonNode node, String field) {
        if (node == null || !node.isObject() || !node.hasNonNull(field)) return null;
        return node.path(field).canConvertToInt() ? node.path(field).intValue() : null;
    }

    private static Instant instantOr(String raw, Instant fallback) {
        if (raw == null) return fallback;
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String valueOr(String value, String fallback) {
        return value != null ? value : fallback;
    }

    private static String routeFromFinalStatus(String finalStatus) {
        if (finalStatus == null) return null;
        return switch (finalStatus.toLowerCase(Locale.ROOT)) {
            case "completed", "blocked", "failed", "budget_exhausted", "tool_completed" -> null;
            default -> finalStatus.toUpperCase(Locale.ROOT);
        };
    }

    public record ConversationResponse(
            List<ConversationRun> items,
            Summary summary,
            int total,
            int hours) {
    }

    public record Summary(long runs, long completed, long blocked, Integer averageLatencyMs) {
    }

    public record ConversationRun(
            String runId,
            String question,
            String answer,
            String route,
            String status,
            Instant startedAt,
            Instant completedAt,
            Integer latencyMs,
            String sessionId,
            String conversationId,
            List<ConversationStep> steps) {
    }

    public record ConversationStep(
            String type,
            Instant timestamp,
            Integer latencyMs,
            String status,
            Map<String, Object> detail) {
    }

    private static final class RunAccumulator {
        private final String runId;
        private String question;
        private String answer;
        private String route;
        private String status = "running";
        private Instant startedAt;
        private Instant completedAt;
        private Instant lastEventAt;
        private Integer latencyMs;
        private String sessionId;
        private String conversationId;
        private final List<ConversationStep> steps = new ArrayList<>();

        private RunAccumulator(String runId) {
            this.runId = runId;
        }

        private ConversationRun toView() {
            Instant effectiveStart = startedAt != null ? startedAt : lastEventAt;
            return new ConversationRun(
                    runId, question, answer, route, status, effectiveStart, completedAt,
                    latencyMs, sessionId, conversationId, List.copyOf(steps));
        }
    }
}
