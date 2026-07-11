package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Forwards to portfolio-notification-service. Uses a static internal
 * service-to-service shared token sourced from
 * {@code NOTIFICATION_INTERNAL_TOKEN}.
 */
@Component
public class NotificationServiceAdapter extends AbstractHttpAdapter {

    @Value("${domain.notification.base-url}")
    private String baseUrl;

    @Value("${domain.notification.timeout-ms:15000}")
    private int timeoutMs;

    @Value("${domain.notification.internal-token:}")
    private String internalToken;

    public NotificationServiceAdapter(WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public String target() {
        return "notification";
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
            spec.header("X-Internal-Token", internalToken);
        }
    }
}
