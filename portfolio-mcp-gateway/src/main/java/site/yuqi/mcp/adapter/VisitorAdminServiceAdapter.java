package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;
import site.yuqi.mcp.validation.VisitorQueryArguments;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Private visitor reads are separate from the sanitized public analytics adapter. */
@Component
public class VisitorAdminServiceAdapter extends AbstractHttpAdapter {
    @Value("${domain.analytics.base-url}") private String baseUrl;
    @Value("${domain.analytics.internal-token:}") private String internalToken;
    @Value("${domain.analytics.timeout-ms:15000}") private int timeoutMs;

    public VisitorAdminServiceAdapter(WebClient.Builder builder) { super(builder); }

    @Override public String target() { return "visitor-admin"; }
    @Override protected String baseUrl() { return baseUrl; }
    @Override protected Duration timeout() { return Duration.ofMillis(timeoutMs); }

    @Override
    public Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args) {
        var endpoint = tool.getEndpoint();
        if (!"ADMIN".equalsIgnoreCase(String.valueOf(args.get("_mcpRole")))
                || !"ADMIN".equals(tool.getRequiredRole()) || tool.getMode() != ToolMode.READ
                || !Set.of("visitor.search_events", "visitor.get_session_events").contains(tool.getName())
                || endpoint == null || !target().equals(endpoint.getTarget())
                || !"GET".equals(endpoint.getMethod()) || !"/api/admin/visitors".equals(endpoint.getPath())) {
            throw new AdapterException("Administrator visitor access is required.", 403);
        }
        if (internalToken == null || internalToken.isBlank()) {
            throw AdapterException.unavailableBeforeDispatch("Visitor detail service credentials are not configured.");
        }
        return super.invoke(tool, args);
    }

    @Override
    protected void prepareArgs(ToolDefinition tool, Map<String, Object> args) {
        var query = VisitorQueryArguments.normalize(tool.getName(), args);
        args.clear();
        args.putAll(query);
    }

    @Override
    protected void decorate(WebClient.RequestHeadersSpec<?> request, Map<String, Object> args) {
        request.header("X-Internal-Token", internalToken.trim());
        request.header("Cache-Control", "no-store");
    }
}
