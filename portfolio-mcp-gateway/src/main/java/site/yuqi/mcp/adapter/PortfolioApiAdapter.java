package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import site.yuqi.mcp.model.ToolDefinition;

/**
 * Public reads carry no credentials. Contact writes use a dedicated service
 * credential so the website can distinguish them from browser form traffic.
 */
@Component
public class PortfolioApiAdapter extends AbstractHttpAdapter {

    @Value("${domain.portfolio.base-url}")
    private String baseUrl;

    @Value("${domain.portfolio.timeout-ms:15000}")
    private int timeoutMs;

    @Value("${domain.portfolio.contact-token:}")
    private String contactToken;

    public PortfolioApiAdapter(WebClient.Builder webClientBuilder) {
        super(webClientBuilder);
    }

    @Override
    public String target() {
        return "portfolio";
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
    protected void decorate(WebClient.RequestHeadersSpec<?> request, Map<String, Object> args,
                            Map<String, Object> controlArgs, ToolDefinition tool) {
        if (!"contact.email_owner".equals(tool.getName())) return;
        if (contactToken == null || contactToken.length() < 32) {
            throw AdapterException.unavailableBeforeDispatch("Contact service authentication is not configured.");
        }
        request.header("X-Portfolio-Contact-Token", contactToken);
    }
}
