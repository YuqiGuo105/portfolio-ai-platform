package site.yuqi.mcp.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.security.PrivacySanitizer;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Forwards privacy-safe aggregate analytics tools to the analytics platform.
 *
 * <p>The public analytics-aggregator service exposes {@code GET /api/public/visits/summary?days=N}
 * — but the tool catalog and LLM prompt use a privacy-friendly {@code startDate/endDate}
 * schema. This adapter translates between the two so the tool contract stays clean
 * without asking the analytics service to grow ad-hoc admin endpoints.
 *
 * <p>Overrides {@link #invoke} entirely (does not call {@code super.invoke}) so we
 * fully control the outbound URI and query params.
 */
@Slf4j
@Component
public class AnalyticsServiceAdapter extends AbstractHttpAdapter {

    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 365;

    @Value("${domain.analytics.base-url}")
    private String baseUrl;

    @Value("${domain.analytics.timeout-ms:15000}")
    private int timeoutMs;

    @Value("${mcp.analytics.min-bucket-count:5}")
    private int minBucketCount;

    public AnalyticsServiceAdapter(WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public String target() {
        return "analytics";
    }

    @Override
    protected String baseUrl() {
        return baseUrl;
    }

    @Override
    protected Duration timeout() {
        return Duration.ofMillis(timeoutMs);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args)
            throws AdapterException {
        if (tool == null || tool.getName() == null || !tool.getName().startsWith("analytics.")) {
            throw new AdapterException("AnalyticsServiceAdapter cannot invoke " + (tool == null ? "null" : tool.getName()));
        }

        int days = computeDays(args);
        String path = "/api/public/visits/summary";

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        try {
            Object raw = client.get()
                    .uri(uri -> uri.path(path).queryParam("days", days).build())
                    .retrieve()
                    .onStatus(status -> status.isError(), resp ->
                            resp.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(new WebClientResponseException(
                                            resp.statusCode().value(),
                                            "Analytics " + resp.statusCode(),
                                            resp.headers().asHttpHeaders(),
                                            body.getBytes(),
                                            null))))
                    .bodyToMono(Object.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            Map<String, Object> result;
            if (raw instanceof Map<?, ?> m) {
                result = new HashMap<>((Map<String, Object>) m);
            } else {
                result = new HashMap<>();
                result.put("data", raw);
            }
            result.put("windowDays", days);
            return PrivacySanitizer.sanitizeAnalyticsResult(result, minBucketCount);
        } catch (WebClientResponseException e) {
            log.warn("Analytics adapter → GET {}?days={} failed: {} body={}",
                    path, days, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new AdapterException(
                    "Analytics service " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                    e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Analytics adapter → GET {}?days={} failed", path, days, e);
            throw new AdapterException("Analytics service call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Compute {@code days} for the public analytics endpoint. Accepts either
     * an explicit {@code days} value or an ISO {@code startDate}/{@code endDate}
     * pair. Enforces a minimum of 7 days (aligns with {@code AnalyticsPrivacyPolicy}).
     */
    private static int computeDays(Map<String, Object> args) {
        Object d = args == null ? null : args.get("days");
        if (d instanceof Number n) return clamp(n.intValue());
        if (d instanceof String s) {
            try { return clamp(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) { /* fall through */ }
        }
        LocalDate start = parseDate(args == null ? null : args.get("startDate"));
        LocalDate end = parseDate(args == null ? null : args.get("endDate"));
        if (start != null && end != null && !end.isBefore(start)) {
            long computed = ChronoUnit.DAYS.between(start, end) + 1;
            return clamp((int) Math.min(MAX_DAYS, computed));
        }
        return DEFAULT_DAYS;
    }

    private static int clamp(int days) {
        return Math.max(DEFAULT_DAYS, Math.min(MAX_DAYS, days));
    }

    private static LocalDate parseDate(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}

