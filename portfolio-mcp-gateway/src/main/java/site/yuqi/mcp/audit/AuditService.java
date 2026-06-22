package site.yuqi.mcp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import site.yuqi.mcp.model.ToolDefinition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Structured audit log for the gateway. Every invoke (read and write) is
 * recorded; writes additionally include the idempotency key and the
 * downstream response status. Sensitive fields are redacted before logging.
 *
 * <p>Sprint 1: SLF4J JSON lines, captured by Cloud Logging. Sprint 2 can
 * additionally INSERT into Supabase {@code mcp_tool_audit_logs}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Set<String> REDACT_KEYS = Set.of(
            "apiKey", "api_key", "authorization", "token", "secret", "password",
            "serviceRoleKey", "service_role_key", "otp");

    private final ObjectMapper objectMapper;

    @Value("${mcp.audit.enabled:true}")
    private boolean enabled;

    public void logInvocation(ToolDefinition tool, String actor, Map<String, Object> args,
                              String idempotencyKey, String status, Integer downstreamStatus,
                              Long latencyMs, String error) {
        if (!enabled) return;

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ts", Instant.now().toString());
        envelope.put("stage", "mcp.tool");
        envelope.put("status", status);
        envelope.put("actor", actor);
        envelope.put("tool", tool.getName());
        envelope.put("mode", tool.getMode());
        envelope.put("riskLevel", tool.getRiskLevel());
        envelope.put("requiredRole", tool.getRequiredRole());
        envelope.put("input", redact(args));
        envelope.put("idempotencyKey", idempotencyKey);
        if (downstreamStatus != null) envelope.put("downstreamStatus", downstreamStatus);
        if (latencyMs != null) envelope.put("latencyMs", latencyMs);
        if (error != null) envelope.put("error", error);

        try {
            log.info("AUDIT {}", objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.info("AUDIT {} (serialization failed: {})", envelope, e.toString());
        }
    }

    private Map<String, Object> redact(Map<String, Object> in) {
        if (in == null) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : in.entrySet()) {
            out.put(e.getKey(), REDACT_KEYS.contains(e.getKey()) ? "***" : e.getValue());
        }
        return out;
    }
}
