package site.yuqi.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Internal carrier for a single tool call from the planner to
 * {@code McpGatewayClient}. {@code idempotencyKey} is REQUIRED for write-mode
 * tools; the gateway enforces this.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocation {

    private String name;
    private Map<String, Object> arguments;
    private String idempotencyKey;
    private String actor;
    private String role;

    /** Filled in after the gateway responds. */
    private Map<String, Object> result;
    private String error;
    private boolean success;
    private long latencyMs;
}
