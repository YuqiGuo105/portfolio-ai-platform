package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/** Admin-only conversation diagnostics. Authorization is enforced by the tool gateway. */
@Component
public class AgentServiceAdapter extends AbstractHttpAdapter {
    @Value("${domain.agent.base-url}") private String baseUrl;
    @Value("${domain.agent.internal-token:}") private String internalToken;

    public AgentServiceAdapter(WebClient.Builder builder) { super(builder); }
    @Override public String target() { return "agent"; }
    @Override protected String baseUrl() { return baseUrl; }
    @Override protected void prepareArgs(site.yuqi.mcp.model.ToolDefinition tool, Map<String, Object> args) {
        if ("agent.get_run_diagnostics".equals(tool.getName())) {
            try { args.put("runId", java.util.UUID.fromString(String.valueOf(args.get("runId"))).toString()); }
            catch (IllegalArgumentException error) { throw new AdapterException("runId must be a UUID."); }
        }
    }
    @Override protected void decorate(WebClient.RequestHeadersSpec<?> request, Map<String, Object> args) {
        if (internalToken == null || internalToken.isBlank()) {
            throw new AdapterException("Agent service credential is not configured.");
        }
        // The agent's SupabaseJwtAuthFilter accepts the trusted gateway token as Bearer.
        request.header("Authorization", "Bearer " + internalToken);
    }
}
