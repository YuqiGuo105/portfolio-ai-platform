package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.mcp.model.ToolDefinition;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
    public Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args)
            throws AdapterException {
        Map<String, Object> result = super.invoke(tool, args);
        return enrichPublishResult(tool, args, result);
    }

    Map<String, Object> enrichPublishResult(
            ToolDefinition tool,
            Map<String, Object> args,
            Map<String, Object> result) {
        if (!"admin.publish_content".equals(tool.getName())
                && !"publication.publish".equals(tool.getName())
                && !"content.rollback".equals(tool.getName())) return result;

        Object identifier = firstPresent(
                result.get("eventId"),
                result.get("correlationId"),
                result.get("traceId"),
                result.get("idempotencyKey"),
                args.get("sourceId"));

        Map<String, Object> verification = new LinkedHashMap<>();
        verification.put("status", "PENDING_VERIFICATION");
        verification.put("identifier", identifier == null ? "" : String.valueOf(identifier));
        verification.put("tools", List.of(
                "notification.get_publication_delivery",
                "admin.get_operation_timeline"));
        verification.put("operationsTimelinePath", "/admin/operations");
        verification.put(
                "message",
                "Publication was accepted. Verify asynchronous Search, RAG, and email delivery before reporting end-to-end success.");

        Map<String, Object> enriched = new LinkedHashMap<>(result);
        enriched.put("verification", verification);
        return enriched;
    }

    private static Object firstPresent(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null && !String.valueOf(candidate).isBlank()) return candidate;
        }
        return null;
    }

    @Override
    protected void prepareArgs(ToolDefinition tool, Map<String, Object> args) {
        if ("admin.get_operation_timeline".equals(tool.getName()) && args.containsKey("query")) {
            args.put("q", args.remove("query"));
            return;
        }
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

    @Override
    protected void decorate(WebClient.RequestHeadersSpec<?> spec, Map<String, Object> args,
                            Map<String, Object> controlArgs) {
        decorate(spec, args);
        header(spec, "X-MCP-Actor", controlArgs.get("_mcpActor"));
        header(spec, "X-MCP-Tool", controlArgs.get("_mcpTool"));
        header(spec, "X-MCP-Client", controlArgs.get("_mcpClient"));
        header(spec, "X-MCP-Model", controlArgs.get("_mcpModel"));
    }

    private static void header(WebClient.RequestHeadersSpec<?> spec, String name, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) spec.header(name, String.valueOf(value));
    }
}
