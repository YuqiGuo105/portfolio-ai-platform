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
            @RequestHeader(value = "X-Actor", required = false) String actor) {

        if (!authorized(auth)) return unauthorized();

        Optional<ToolDefinition> toolOpt = registry.find(name);
        if (toolOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(StructuredErrorResponse.of("tool_not_found", "Unknown tool: " + name, name));
        }
        ToolDefinition tool = toolOpt.get();
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

        // 2. Risk gate (confirm + dryRun)
        RiskGateValidator.Outcome risk = riskGateValidator.check(tool, args);
        if (!risk.allowed()) {
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "rejected_risk", null, 0L, risk.reason());
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(StructuredErrorResponse.of("risk_gate", risk.reason(), tool.getName()));
        }

        // 3. Idempotency (writes only)
        if (tool.getMode() != null && tool.getMode().name().equals("WRITE")) {
            Optional<IdempotencyKeyService.CachedResult> cached = idempotencyKeyService.lookup(idempotencyKey);
            if (cached.isPresent()) {
                auditService.logInvocation(tool, actor, args, idempotencyKey,
                        "replay", null, 0L, null);
                return ResponseEntity.ok(cached.get().getResult());
            }
        }

        // 4. Dispatch to adapter
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
}
