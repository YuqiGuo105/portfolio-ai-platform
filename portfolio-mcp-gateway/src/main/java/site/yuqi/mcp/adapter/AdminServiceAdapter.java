package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.mcp.model.ToolDefinition;

import java.time.Duration;
import java.util.HashMap;
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

    @Value("${domain.admin.admin-secret:}")
    private String adminSecret;

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
    protected void prepareArgs(ToolDefinition tool, Map<String, Object> args) {
        if ("admin.search_content".equals(tool.getName()) && args.containsKey("sourceType")) {
            args.put("type", normalizeSourceType(args.remove("sourceType")));
            return;
        }
        if ("admin.create_content_draft".equals(tool.getName())
                || "admin.update_content".equals(tool.getName())) {
            wrapContentMutation(args);
            return;
        }
        if (args.containsKey("sourceType")) {
            args.put("sourceType", normalizeSourceType(args.get("sourceType")));
        }
        if (tool.getName().startsWith("admin.reindex_")) {
            // The admin API enqueues the real job. dryRun is a gateway concern
            // and is not part of the downstream endpoint contract.
            args.remove("dryRun");
        }
    }

    private static void wrapContentMutation(Map<String, Object> args) {
        Object sourceType = normalizeSourceType(args.get("sourceType"));
        Object sourceId = args.get("sourceId");
        Object changeNote = args.get("changeNote");

        Map<String, Object> data = new HashMap<>(args);
        data.remove("sourceType");
        data.remove("sourceId");
        data.remove("changeNote");
        Object body = data.remove("body");
        if (body != null) data.put("content", body);

        args.clear();
        args.put("sourceType", sourceType);
        if (sourceId != null) args.put("sourceId", sourceId);
        args.put("data", data);
        args.put("publish", false);
        if (changeNote != null) args.put("changeNote", changeNote);
    }

    private static Object normalizeSourceType(Object value) {
        if (value == null) return null;
        return "LIFE".equalsIgnoreCase(String.valueOf(value)) ? "LIFE_BLOG" : value;
    }

    @Override
    protected void decorate(WebClient.RequestHeadersSpec<?> spec, Map<String, Object> args) {
        if (adminSecret == null || adminSecret.isBlank()) {
            throw new AdapterException("Admin service credential is not configured.");
        }
        spec.header("X-Admin-Secret", adminSecret);
    }
}
