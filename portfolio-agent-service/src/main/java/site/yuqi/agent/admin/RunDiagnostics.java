package site.yuqi.agent.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded, deterministic diagnostics over recorded evidence, not model speculation. */
final class RunDiagnostics {
    private RunDiagnostics() {}

    static Map<String, Object> describe(UUID runId,
            List<AdminConversationEventRepository.EventRow> rows, ObjectMapper mapper) {
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> signals = new LinkedHashSet<>();
        Set<String> traces = new LinkedHashSet<>();
        boolean started = false, completed = false;
        for (var row : rows.stream().limit(500).toList()) {
            try {
                JsonNode event = mapper.readTree(row.payloadJson());
                if (!runId.toString().equals(event.path("runId").asText())) continue;
                String type = event.path("eventType").asText(row.category());
                JsonNode payload = event.path("payload");
                started |= "agent_run.started".equals(type);
                completed |= "agent_run.completed".equals(type);
                String trace = event.path("traceId").asText("");
                if (!trace.isBlank()) traces.add(trace);
                if (type.equals("retrieval.completed")) {
                    if (payload.path("zeroHit").asBoolean()) signals.add("RETRIEVAL_ZERO_HIT");
                    if (payload.path("retrievalStrategy").asText().equals("unavailable")) signals.add("RETRIEVAL_UNAVAILABLE");
                    if (payload.path("returnedChunks").asInt() > 0 && !payload.has("sources")) signals.add("RETRIEVAL_PROVENANCE_MISSING");
                }
                if (type.equals("safety.check_completed") && payload.path("category").asText().equals("UNKNOWN")) {
                    signals.add("SAFETY_CHECK_UNAVAILABLE_OR_UNPARSEABLE");
                }
                if (Set.of("failed", "blocked", "budget_exhausted").contains(event.path("status").asText())) signals.add("RUN_HAS_FAILURE_OR_BLOCK");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("eventId", row.id());
                item.put("timestamp", row.createdAt());
                item.put("type", type);
                item.put("record", sanitize(event, 0));
                events.add(item);
            } catch (Exception ignored) {
                signals.add("MALFORMED_EVENT");
            }
        }
        if (!rows.isEmpty() && !started) signals.add("START_EVENT_MISSING");
        if (!rows.isEmpty() && !completed) signals.add("TERMINAL_EVENT_NOT_RECORDED");
        if (traces.size() > 1) signals.add("TRACE_FRAGMENTED_USE_RUN_ID");
        if (rows.size() > 500) signals.add("EVENT_LIMIT_REACHED");
        return Map.of("runId", runId.toString(), "found", !rows.isEmpty(),
                "storage", "outbox_event", "events", events, "eventCount", events.size(),
                "truncated", rows.size() > 500, "signals", List.copyOf(signals),
                "note", "Signals describe recorded evidence, not an answer-quality verdict. Missing provenance cannot be reconstructed retroactively. No model hidden reasoning is stored or returned.");
    }

    static Object sanitize(JsonNode value, int depth) {
        if (depth > 10) return "[depth limit]";
        if (value.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            value.fields().forEachRemaining(field -> {
                String key = field.getKey().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
                boolean secret = key.contains("password") || key.contains("secret") || key.contains("authorization")
                        || key.contains("cookie") || key.endsWith("token") || key.equals("apikey")
                        || Set.of("chainofthought", "hiddenreasoning", "rawreasoning", "internalreasoning").contains(key);
                out.put(field.getKey(), secret ? "[redacted]" : sanitize(field.getValue(), depth + 1));
            });
            return out;
        }
        if (value.isArray()) {
            List<Object> out = new ArrayList<>();
            value.forEach(item -> { if (out.size() < 50) out.add(sanitize(item, depth + 1)); });
            if (value.size() > 50) out.add("[array truncated]");
            return out;
        }
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        String text = value.asText();
        return text.length() <= 4000 ? text : text.substring(0, 4000) + " [truncated]";
    }
}
