package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@Component
public class CostServiceAdapter extends AbstractHttpAdapter {
    @Value("${domain.agent.base-url}") private String baseUrl;
    @Value("${domain.agent.internal-token:}") private String internalToken;
    @Value("${domain.agent.timeout-ms:15000}") private int timeoutMs;

    public CostServiceAdapter(WebClient.Builder builder) { super(builder); }
    @Override public String target() { return "cost"; }
    @Override protected String baseUrl() { return baseUrl; }
    @Override protected Duration timeout() { return Duration.ofMillis(timeoutMs); }
    @Override protected void decorate(WebClient.RequestHeadersSpec<?> spec, Map<String, Object> args) {
        if (internalToken == null || internalToken.isBlank()) throw new AdapterException("Agent credential is not configured.");
        spec.header("X-Internal-Token", internalToken);
    }
}
