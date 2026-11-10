package site.yuqi.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per-subscription provider receipts, never shared between conversations. */
final class GenerationUsage {
    private long input = -1;
    private long total = -1;

    synchronized void observe(JsonNode receipt) {
        JsonNode prompt = receipt.path("promptTokenCount");
        JsonNode tokens = receipt.path("totalTokenCount");
        if (!prompt.isIntegralNumber() || !tokens.isIntegralNumber()
                || !prompt.canConvertToLong() || !tokens.canConvertToLong()) return;
        long in = prompt.longValue(), all = tokens.longValue();
        if (in < 0 || all < in || all > 10_000_000 || all < total) return;
        input = in;
        total = all;
    }

    synchronized Map<String, Object> snapshot(String model) {
        if (input < 0) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputTokens", input);
        result.put("outputTokens", total - input);
        // Match the gateway's conservative rates; not a provider invoice or run total.
        if ("gemini-2.5-flash-lite".equals(model)) {
            result.put("costUsd", (input * 0.10 + (total - input) * 0.40) / 1_000_000);
        } else if ("qwen3.7-flash-2026-07-15".equals(model)) {
            result.put("costUsd", (input * 0.20 + (total - input) * 0.80) / 1_000_000);
        }
        return Map.copyOf(result);
    }
}
