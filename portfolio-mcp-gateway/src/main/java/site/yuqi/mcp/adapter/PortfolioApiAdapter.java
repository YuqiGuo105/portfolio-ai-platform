package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Forwards to the public Portfolio Next.js API (search, contact). Bears no
 * credentials — these endpoints are already public.
 */
@Component
public class PortfolioApiAdapter extends AbstractHttpAdapter {

    @Value("${domain.portfolio.base-url}")
    private String baseUrl;

    @Value("${domain.portfolio.timeout-ms:15000}")
    private int timeoutMs;

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
}
