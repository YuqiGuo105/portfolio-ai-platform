package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Forwards alert-rule management tools to the analytics-alerts-service.
 * Target name: "alerts"
 */
@Component
public class AlertsServiceAdapter extends AbstractHttpAdapter {

    @Value("${domain.alerts.base-url}")
    private String baseUrl;

    @Value("${domain.alerts.internal-token:}")
    private String internalToken;

    @Value("${domain.alerts.timeout-ms:15000}")
    private int timeoutMs;

    public AlertsServiceAdapter(WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public String target() {
        return "alerts";
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
    protected void decorate(WebClient.RequestHeadersSpec<?> spec, java.util.Map<String, Object> args) {
        if (internalToken != null && !internalToken.isBlank()) {
            spec.header("Authorization", "Bearer " + internalToken);
        }
    }
}
