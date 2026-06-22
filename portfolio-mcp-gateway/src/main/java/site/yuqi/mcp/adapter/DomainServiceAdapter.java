package site.yuqi.mcp.adapter;

import site.yuqi.mcp.model.ToolDefinition;

import java.util.Map;

/**
 * Domain-service adapter. Implementations forward a validated tool
 * invocation to the appropriate backend (Portfolio Next.js, admin-service,
 * notification-service) and return a JSON-ish result map.
 *
 * <p>The gateway picks the adapter from {@link ToolDefinition#getEndpoint()}.{@code target}
 * — see the {@code target} field in {@code tool-catalog.yaml}.
 */
public interface DomainServiceAdapter {

    /** Adapter id, matching the YAML {@code endpoint.target} value. */
    String target();

    /**
     * Forward the call. Implementations must:
     * <ul>
     *   <li>Substitute path params (e.g. {@code {id}}) from {@code args}.</li>
     *   <li>Pass remaining args as query string (GET) or JSON body (POST/PATCH).</li>
     *   <li>Throw {@link AdapterException} on transport / non-2xx response.</li>
     * </ul>
     */
    Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args) throws AdapterException;
}
