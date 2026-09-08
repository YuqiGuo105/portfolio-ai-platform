package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;

@Component
public class KnowledgeServiceAdapter extends AbstractHttpAdapter {
    @Value("${domain.knowledge.base-url}") private String baseUrl;
    @Value("${domain.knowledge.internal-token:}") private String token;
    public KnowledgeServiceAdapter(WebClient.Builder builder) { super(builder); }
    @Override public String target() { return "knowledge"; }
    @Override protected String baseUrl() { return baseUrl; }
    @Override protected void decorate(WebClient.RequestHeadersSpec<?> request, Map<String, Object> args) {
        if (token == null || token.isBlank()) throw new AdapterException("Knowledge credential is not configured.");
        request.header("X-Internal-Token", token);
    }
}
