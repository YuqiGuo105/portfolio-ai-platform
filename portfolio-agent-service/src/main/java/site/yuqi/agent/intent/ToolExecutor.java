package site.yuqi.agent.intent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.yuqi.agent.client.McpGatewayClient;
import site.yuqi.agent.model.ToolInvocation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Final dispatcher. Hands the resolved tool name + arguments to the MCP
 * gateway, which validates again, audits, and forwards to the correct
 * domain service (admin-service / notification-service / Portfolio API).
 *
 * <p>The executor itself NEVER writes to Supabase tables or constructs
 * Kafka payloads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolExecutor {

    private final McpGatewayClient gatewayClient;
    private final AuditService auditService;

    public IntentResponse execute(IntentRequest req, ToolDefinition tool, Map<String, Object> args,
                                  IntentResult intent) {
        String idempotencyKey = (tool.riskLevel() == RiskLevel.READ_ONLY)
                ? null
                : UUID.randomUUID().toString();
        Map<String, Object> invocationArgs = new HashMap<>(args == null ? Map.of() : args);
        if (tool.requiresConfirmation() || tool.riskLevel() != RiskLevel.READ_ONLY) {
            invocationArgs.put("_confirmed", true);
        }
        if (tool.name().startsWith("analytics.")) {
            invocationArgs.put("_confirmedTimeRange", true);
        }
        if ("alerts.apply_change".equals(tool.name())) {
            invocationArgs.put("idempotencyKey", idempotencyKey);
        }

        auditService.logExecutionStart(req, tool.name(), invocationArgs);

        ToolInvocation inv = ToolInvocation.builder()
                .name(tool.name())
                .arguments(invocationArgs)
                .idempotencyKey(idempotencyKey)
                .actor(req.getUserEmail() != null ? req.getUserEmail() : req.getUserId())
                .role(highestRole(req.getUserRoles()))
                .build();

        ToolInvocation result;
        try {
            result = gatewayClient.invoke(inv).block();
        } catch (Exception e) {
            auditService.logExecutionResult(req, tool.name(), false, 0, e.getMessage());
            return IntentResponse.error("Tool execution failed: " + e.getMessage());
        }
        if (result == null) {
            auditService.logExecutionResult(req, tool.name(), false, 0, "null result");
            return IntentResponse.error("Tool returned no result.");
        }

        auditService.logExecutionResult(req, tool.name(), result.isSuccess(),
                result.getLatencyMs(), result.getError());

        if (!result.isSuccess()) {
            return IntentResponse.error("Tool error: " + result.getError());
        }
        return IntentResponse.ok(intent, result.getResult());
    }

    private String highestRole(String roles) {
        if (roles == null || roles.isBlank()) return null;
        var normalized = java.util.Arrays.stream(roles.split(","))
                .map(String::trim)
                .map(String::toUpperCase)
                .collect(java.util.stream.Collectors.toSet());
        if (normalized.contains("ADMIN")) return "ADMIN";
        if (normalized.contains("PUBLISHER")) return "PUBLISHER";
        if (normalized.contains("EDITOR")) return "EDITOR";
        if (normalized.contains("VIEWER")) return "VIEWER";
        return null;
    }
}
