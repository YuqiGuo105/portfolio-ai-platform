package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.security.PrivacySanitizer;

import java.time.Duration;
import java.util.Map;

/**
 * Forwards privacy-safe aggregate analytics tools to the analytics platform.
 * Raw visitor data is stripped even if the downstream service accidentally
 * returns it.
 */
@Component
public class AnalyticsServiceAdapter extends AbstractHttpAdapter {

    @Value("${domain.analytics.base-url}")
    private String baseUrl;

    @Value("${domain.analytics.timeout-ms:15000}")
    private int timeoutMs;

    @Value("${domain.analytics.internal-token:}")
    private String internalToken;

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
    protected void decorate(WebClient.RequestHeadersSpec<?> spec, Map<String, Object> args) {
        if (internalToken != null && !internalToken.isBlank()) {
            spec.header("Authorization", "Bearer " + internalToken);
        }
    }

    @Override
    public Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args) throws AdapterException {
        Map<String, Object> result = super.invoke(tool, args);
        return PrivacySanitizer.sanitizeAnalyticsResult(result, minBucketCount);
    }
}
