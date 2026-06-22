package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Forwards to portfolio-admin-service. Admin endpoints are the gateway's
 * write-mode collaborators — admin-service owns Kafka emission for
 * publish / reindex / retry. This adapter does NOT construct any event
 * payload; it only POSTs to the admin endpoint.
 */
@Component
public class AdminServiceAdapter extends AbstractHttpAdapter {

    @Value("${domain.admin.base-url}")
    private String baseUrl;

    @Value("${domain.admin.timeout-ms:15000}")
    private int timeoutMs;

    public AdminServiceAdapter(WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public String target() {
        return "admin";
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
        // admin-service uses Supabase-JWT bearer auth at /api/admin/**.
        // The caller's bearer is propagated by ToolController via header — see there.
    }
}
