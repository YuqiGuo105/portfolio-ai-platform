package site.yuqi.mcp.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.mcp.adapter.AdapterException;
import site.yuqi.mcp.adapter.AdapterResolver;
import site.yuqi.mcp.adapter.DomainServiceAdapter;
import site.yuqi.mcp.audit.AuditService;
import site.yuqi.mcp.idempotency.IdempotencyKeyService;
import site.yuqi.mcp.model.StructuredErrorResponse;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.registry.ToolRegistry;
import site.yuqi.mcp.security.AnalyticsPrivacyPolicy;
import site.yuqi.mcp.security.RiskGateValidator;
import site.yuqi.mcp.validation.ParameterValidator;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Public surface of the MCP gateway.
 *
 * <pre>
 *   POST /api/tools/{name}/invoke   – main dispatch
 *   GET  /api/tools                 – list the catalog (sanitized)
 *   GET  /api/health                – liveness
 * </pre>
 *
 * <p>Auth model (Sprint 1): inbound bearer must match
 * {@code MCP_GATEWAY_INTERNAL_TOKEN}. The agent service is the only legit
 * caller. The gateway is NOT a public surface.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ToolController {

    private final ToolRegistry registry;
    private final ParameterValidator parameterValidator;
    private final AnalyticsPrivacyPolicy analyticsPrivacyPolicy;
    private final RiskGateValidator riskGateValidator;
    private final IdempotencyKeyService idempotencyKeyService;
    private final AdapterResolver adapterResolver;
    private final AuditService auditService;

    @Value("${mcp.internal-token:}")
    private String internalToken;

    /**
     * Escape hatch for local development. When {@code true} and no
     * {@code mcp.internal-token} is configured, requests without a valid
     * bearer are still accepted. <strong>Must stay {@code false} in prod.</strong>
     * When {@code false} (default) an unset internal-token means every
     * protected request 401s — fail-closed by design.
     */
    @Value("${mcp.auth.allow-anonymous:false}")
    private boolean allowAnonymous;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @GetMapping("/tools")
    public ResponseEntity<?> listTools(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (!authorized(auth)) return unauthorized();
        return ResponseEntity.ok(registry.all());
    }

    @PostMapping("/tools/{name}/invoke")
    public ResponseEntity<?> invoke(
            @PathVariable String name,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Actor", required = false) String actor,
            @RequestHeader(value = "X-Role", required = false) String role) {

        if (!authorized(auth)) return unauthorized();

        Optional<ToolDefinition> toolOpt = registry.find(name);
        if (toolOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StructuredErrorResponse.of("tool_not_found", "Unknown tool: " + name, name));
        }
        ToolDefinition tool = toolOpt.get();

        // RBAC: enforce requiredRole
        if (!hasRequiredRole(tool, role)) {
            auditService.logInvocation(tool, actor, body, idempotencyKey,
                    "rejected_role", null, 0L, "required=" + tool.getRequiredRole() + " actual=" + role);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(StructuredErrorResponse.of("insufficient_role",
                            "Tool requires role " + tool.getRequiredRole() + " but caller has " + (role != null ? role : "none"),
                            tool.getName()));
        }

        Map<String, Object> args = body == null ? new HashMap<>() : new HashMap<>(body);

        // 1. Parameter validation
        ParameterValidator.ValidationResult pv = parameterValidator.validate(tool, args);
        if (!pv.isValid()) {
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "rejected_validation", null, 0L, String.join("; ", pv.getErrors()));
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(StructuredErrorResponse.of("invalid_parameters", "Parameter validation failed.", tool.getName())
                            .withDetail("errors", pv.getErrors()));
        }

        // 2. Privacy gate for aggregate analytics reads.
        AnalyticsPrivacyPolicy.Outcome privacy = analyticsPrivacyPolicy.check(tool, args);
        if (!privacy.allowed()) {
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "rejected_privacy", null, 0L, privacy.reason());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(StructuredErrorResponse.of("privacy_gate", privacy.reason(), tool.getName()));
        }

        // 3. Risk gate (confirm + dryRun)
        RiskGateValidator.Outcome risk = riskGateValidator.check(tool, args);
        if (!risk.allowed()) {
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "rejected_risk", null, 0L, risk.reason());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(StructuredErrorResponse.of("risk_gate", risk.reason(), tool.getName()));
        }

        // 4. Idempotency (writes only)
        if (tool.getMode() != null && tool.getMode().name().equals("WRITE")) {
            Optional<IdempotencyKeyService.CachedResult> cached = idempotencyKeyService.lookup(idempotencyKey);
            if (cached.isPresent()) {
                auditService.logInvocation(tool, actor, args, idempotencyKey,
                        "replay", null, 0L, null);
                return ResponseEntity.ok(cached.get().getResult());
            }
        }

        // 5. Dispatch to adapter
        String target = tool.getEndpoint() == null ? null : tool.getEndpoint().getTarget();
        DomainServiceAdapter adapter = target == null ? null : adapterResolver.find(target).orElse(null);
        if (adapter == null) {
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "no_adapter", null, 0L, "target=" + target);
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(StructuredErrorResponse.of("no_adapter",
                            "No adapter wired for target: " + target, tool.getName()));
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = adapter.invoke(tool, args);
            long latency = System.currentTimeMillis() - start;
            idempotencyKeyService.remember(idempotencyKey, tool.getName(), result);
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "ok", 200, latency, null);
            return ResponseEntity.ok(result);
        } catch (AdapterException e) {
            long latency = System.currentTimeMillis() - start;
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "downstream_error", e.getStatusCode(), latency, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(StructuredErrorResponse.of("downstream_error", e.getMessage(), tool.getName())
                            .withDetail("downstreamStatus", e.getStatusCode()));
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "error", null, latency, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(StructuredErrorResponse.of("internal_error", e.getMessage(), tool.getName()));
        }
    }

    private boolean authorized(String authorizationHeader) {
        if (internalToken == null || internalToken.isBlank()) {
            // fail-closed: without a configured token we cannot verify
            // anyone. Only local dev may explicitly opt out via
            // mcp.auth.allow-anonymous=true.
            return allowAnonymous;
        }
        if (authorizationHeader == null) return false;
        String expected = "Bearer " + internalToken;
        return authorizationHeader.equals(expected);
    }

    private ResponseEntity<StructuredErrorResponse> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(StructuredErrorResponse.of("unauthorized", "Missing or invalid bearer token."));
    }

    /**
     * RBAC enforcement: checks if the caller's role satisfies the tool's requiredRole.
     * Role hierarchy: ADMIN > PUBLISHER > EDITOR > VIEWER.
     * The internal agent-service/mcp-server calls with X-Role header.
     * If no requiredRole is set on the tool, any authenticated caller passes.
     */
    private boolean hasRequiredRole(ToolDefinition tool, String callerRole) {
        String required = tool.getRequiredRole();
        if (required == null || required.isBlank()) return true;
        if (callerRole == null || callerRole.isBlank()) {
            // Legacy callers without X-Role header: grant VIEWER level for backward compat
            return roleLevel(required) <= roleLevel("VIEWER");
        }
        return roleLevel(callerRole) >= roleLevel(required);
    }

    private static int roleLevel(String role) {
        return switch (role.toUpperCase()) {
            case "ADMIN" -> 4;
            case "PUBLISHER" -> 3;
            case "EDITOR" -> 2;
            case "VIEWER" -> 1;
            default -> 0;
        };
    }
}
