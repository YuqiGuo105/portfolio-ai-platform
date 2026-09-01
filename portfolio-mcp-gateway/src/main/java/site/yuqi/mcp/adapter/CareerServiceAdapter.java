package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/** Forwards owner-only career tools to portfolio-application-copilot. */
@Component
public class CareerServiceAdapter extends AbstractHttpAdapter {
    @Value("${domain.career.base-url}") private String baseUrl;
    @Value("${domain.career.internal-token:}") private String internalToken;
    @Value("${domain.career.cloud-run-id-token-enabled:false}") private boolean cloudRunIdTokenEnabled;
    @Value("${domain.career.timeout-ms:25000}") private int timeoutMs;

    private final CloudRunIdentityTokenProvider identityTokenProvider;

    public CareerServiceAdapter(
            WebClient.Builder webClientBuilder,
            CloudRunIdentityTokenProvider identityTokenProvider) {
        super(webClientBuilder);
        this.identityTokenProvider = identityTokenProvider;
    }
    @Override public String target() { return "career"; }
    @Override protected String baseUrl() { return baseUrl; }
    @Override protected Duration timeout() { return Duration.ofMillis(timeoutMs); }
    @Override protected void decorate(WebClient.RequestHeadersSpec<?> spec, Map<String, Object> args) {
        if (internalToken == null || internalToken.isBlank()) throw new AdapterException("Career service credential is not configured.");
        spec.header("X-Internal-Token", internalToken);
        if (cloudRunIdTokenEnabled) {
            spec.headers(headers -> headers.setBearerAuth(identityTokenProvider.tokenFor(baseUrl)));
        }
    }
}
