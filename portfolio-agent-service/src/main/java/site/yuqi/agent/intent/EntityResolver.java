package site.yuqi.agent.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import site.yuqi.agent.client.McpGatewayClient;
import site.yuqi.agent.model.ToolInvocation;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves human-named entities (e.g. "the latest Kafka blog") into concrete
 * backend IDs by issuing READ-ONLY tool calls — never by guessing.
 *
 * <p>Resolution outcomes:
 * <ul>
 *   <li>{@link Outcome#READY}      – arguments are concrete, executor can run.</li>
 *   <li>{@link Outcome#CLARIFY}    – ambiguous (0 or many candidates); ask user.</li>
 *   <li>{@link Outcome#GATEWAY_ERR}– upstream call failed; surface to user.</li>
 * </ul>
 *
 * <p>Sprint 1 implements the two flows called out in the spec:
 * <ul>
 *   <li>{@code admin.publish_content} with {@code keyword/sourceType} but no
 *       {@code sourceId} → invokes {@code admin.search_content} and either
 *       picks the single hit or returns options.</li>
 *   <li>{@code admin.retry_indexing_job} with {@code status=FAILED} but no
 *       {@code jobId} → invokes {@code admin.list_indexing_jobs} similarly.</li>
 * </ul>
 * All other tools pass-through with whatever entities the LLM extracted.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityResolver {

    public enum Outcome { READY, CLARIFY, GATEWAY_ERR }

    private final McpGatewayClient gatewayClient;

    public EntityResolutionResult resolve(IntentResult intent, ToolDefinition tool, IntentRequest request) {
        Map<String, Object> args = new HashMap<>(intent.entities());

        // ── Publish / reindex flows that need sourceId from keyword ─────
        if ((tool.intent() == IntentType.ADMIN_PUBLISH_CONTENT
                || tool.intent() == IntentType.ADMIN_REINDEX_RAG
                || tool.intent() == IntentType.ADMIN_REINDEX_SEARCH
                || tool.intent() == IntentType.ADMIN_UPDATE_CONTENT)
                && !args.containsKey("sourceId")
                && args.containsKey("keyword")) {
            return resolveBySearch(args);
        }

        // ── Retry job flow that needs jobId from "the failed job" ───────
        if (tool.intent() == IntentType.ADMIN_RETRY_INDEXING_JOB
                && !args.containsKey("jobId")) {
            return resolveFailedJob(args);
        }

        if (isAnalyticsIntent(tool.intent())) {
            normalizeAnalyticsTimeRange(args);
        }

        return EntityResolutionResult.builder()
                .outcome(Outcome.READY)
                .resolvedArguments(args)
                .build();
    }

    private boolean isAnalyticsIntent(IntentType intent) {
        return intent == IntentType.ANALYTICS_GET_VISITOR_SUMMARY
                || intent == IntentType.ANALYTICS_GET_TOP_PAGES
                || intent == IntentType.ANALYTICS_GET_REFERRER_SUMMARY;
    }

    private void normalizeAnalyticsTimeRange(Map<String, Object> args) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate end = parseDate(args.get("endDate"));
        LocalDate start = parseDate(args.get("startDate"));

        if (start == null && end == null) {
            end = today;
            start = end.minusDays(6);
            args.put("_timeRangeDefaulted", true);
        } else if (start == null) {
            start = end.minusDays(6);
            args.put("_timeRangeDefaulted", true);
        } else if (end == null) {
            end = today;
            args.put("_timeRangeDefaulted", true);
        }

        if (end.isBefore(start)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
            args.put("_timeRangeReordered", true);
        }

        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < 7) {
            start = end.minusDays(6);
            args.put("_privacyWindowAdjusted", true);
        }

        args.put("startDate", start.toString());
        args.put("endDate", end.toString());
        args.remove("timeRangePreset");
    }

    private LocalDate parseDate(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private EntityResolutionResult resolveBySearch(Map<String, Object> args) {
        Map<String, Object> searchArgs = new HashMap<>();
        searchArgs.put("keyword", args.get("keyword"));
        if (args.get("sourceType") != null) searchArgs.put("sourceType", args.get("sourceType"));
        searchArgs.put("limit", 5);

        ToolInvocation inv = invokeReadOnly("admin.search_content", searchArgs);
        if (!inv.isSuccess()) {
            return EntityResolutionResult.builder()
                    .outcome(Outcome.GATEWAY_ERR)
                    .question("Could not search for matching content: " + inv.getError())
                    .build();
        }

        List<Map<String, Object>> hits = extractItems(inv.getResult());
        if (hits.isEmpty()) {
            return EntityResolutionResult.builder()
                    .outcome(Outcome.CLARIFY)
                    .question("No content found matching keyword \"" + args.get("keyword") + "\". Could you be more specific?")
                    .build();
        }
        if (hits.size() == 1) {
            args.put("sourceId", hits.get(0).get("id"));
            if (hits.get(0).get("sourceType") != null) {
                args.putIfAbsent("sourceType", hits.get(0).get("sourceType"));
            }
            return EntityResolutionResult.builder()
                    .outcome(Outcome.READY)
                    .resolvedArguments(args)
                    .build();
        }
        return EntityResolutionResult.builder()
                .outcome(Outcome.CLARIFY)
                .question("Found multiple matches. Which one?")
                .options(hits)
                .build();
    }

    private EntityResolutionResult resolveFailedJob(Map<String, Object> args) {
        Map<String, Object> listArgs = new HashMap<>();
        listArgs.put("status", "FAILED");
        listArgs.put("limit", 5);

        ToolInvocation inv = invokeReadOnly("admin.list_indexing_jobs", listArgs);
        if (!inv.isSuccess()) {
            return EntityResolutionResult.builder()
                    .outcome(Outcome.GATEWAY_ERR)
                    .question("Could not list failed jobs: " + inv.getError())
                    .build();
        }
        List<Map<String, Object>> jobs = extractItems(inv.getResult());
        if (jobs.isEmpty()) {
            return EntityResolutionResult.builder()
                    .outcome(Outcome.CLARIFY)
                    .question("There are no failed indexing jobs right now — nothing to retry.")
                    .build();
        }
        if (jobs.size() == 1) {
            args.put("jobId", jobs.get(0).get("id"));
            return EntityResolutionResult.builder()
                    .outcome(Outcome.READY)
                    .resolvedArguments(args)
                    .build();
        }
        return EntityResolutionResult.builder()
                .outcome(Outcome.CLARIFY)
                .question("Multiple failed jobs — which one should I retry?")
                .options(jobs)
                .build();
    }

    private ToolInvocation invokeReadOnly(String name, Map<String, Object> args) {
        ToolInvocation inv = ToolInvocation.builder()
                .name(name)
                .arguments(args)
                .build();
        try {
            return gatewayClient.invoke(inv).block();
        } catch (Exception e) {
            log.warn("Read-only resolver call {} failed", name, e);
            inv.setSuccess(false);
            inv.setError(e.getMessage());
            return inv;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(Map<String, Object> result) {
        if (result == null) return List.of();
        Object items = result.get("items");
        if (items == null) items = result.get("results");
        if (items == null) items = result.get("data");
        if (items instanceof List<?> list) {
            return list.stream()
                    .filter(o -> o instanceof Map<?, ?>)
                    .map(o -> (Map<String, Object>) o)
                    .toList();
        }
        return List.of();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EntityResolutionResult {
        private Outcome outcome;
        private Map<String, Object> resolvedArguments;
        private String question;
        private List<Map<String, Object>> options;

        public boolean isReady() { return outcome == Outcome.READY; }
        public boolean needsClarification() { return outcome == Outcome.CLARIFY || outcome == Outcome.GATEWAY_ERR; }
    }
}
