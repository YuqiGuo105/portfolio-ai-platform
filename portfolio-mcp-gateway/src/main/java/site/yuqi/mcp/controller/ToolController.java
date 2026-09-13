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
            @RequestHeader(value = "X-Role", required = false) String role,
            @RequestHeader(value = "X-MCP-Client", required = false) String mcpClient,
            @RequestHeader(value = "X-MCP-Model", required = false) String mcpModel) {

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
        if ("alerts.prepare_change".equals(tool.getName())) {
            args.put("actor", actor == null || actor.isBlank() ? "authenticated-admin" : actor);
        }

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

        if (tool.getMode()==site.yuqi.mcp.model.ToolMode.WRITE && Boolean.TRUE.equals(args.get("dryRun"))) {
            return ResponseEntity.ok(Map.of("tool",name,"state","PREVIEW","dryRun",true,"executed",false,
                    "nextAction","CONFIRM_AND_INVOKE_WITH_NEW_INTENT_KEY"));
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

        String principal = actor == null || actor.isBlank() ? "authenticated-admin" : actor;
        if (name.startsWith("operation.")) args.put("principal",principal);
        boolean write = tool.getMode() != null && tool.getMode().name().equals("WRITE");
        Map<String,Object> claim = null;
        if (write) {
            try {
                claim = idempotencyKeyService.claim(principal, name, idempotencyKey, args);
                if (!Boolean.TRUE.equals(claim.get("dispatch"))) {
                    Map<String,Object> replay = new java.util.LinkedHashMap<>();
                    if (claim.get("response") instanceof Map<?,?> response)
                        response.forEach((k,v) -> replay.put(String.valueOf(k),v));
                    replay.put("operation", operationView(claim));
                    int status = claim.get("httpStatus") instanceof Number n ? n.intValue() : 409;
                    return ResponseEntity.status(status).body(replay);
                }
            } catch (IdempotencyKeyService.LedgerException e) {
                return ResponseEntity.status(e.status).body(StructuredErrorResponse.of(e.getMessage(),
                        "Write admission failed; reuse the same key when retrying. No downstream call was made.",name));
            }
        }
        args.put("_mcpActor", principal);
        args.put("_mcpTool", tool.getName());
        // Only trusted service headers supply role context; overwrite caller control arguments.
        args.put("_mcpRole", role == null ? "VIEWER" : role);
        if (idempotencyKey != null) args.put("_idempotencyKey",idempotencyKey);
        if (claim != null) args.put("_operationId",claim.get("operationId"));
        if (mcpClient != null) args.put("_mcpClient", mcpClient);
        if (mcpModel != null) args.put("_mcpModel", mcpModel);

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> result = adapter.invoke(tool, args);
            long latency = System.currentTimeMillis() - start;
            if (claim != null) {
                Map<String,Object> completed = idempotencyKeyService.complete(principal,claim,"SUCCEEDED",200,result);
                result = new java.util.LinkedHashMap<>(result);
                result.put("operation",completed);
            }
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "ok", 200, latency, null);
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(result);
        } catch (AdapterException e) {
            long latency = System.currentTimeMillis() - start;
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "downstream_error", e.getStatusCode(), latency, e.getMessage());
            boolean notDispatched = e.isNotDispatched();
            boolean definite = e.getStatusCode()!=null && e.getStatusCode()>=400 && e.getStatusCode()<500
                    && e.getStatusCode()!=408;
            String state = notDispatched ? "RETRYABLE" : definite ? "FAILED_FINAL" : "UNKNOWN";
            boolean retryable = notDispatched || (!write && (e.getStatusCode()==null || e.getStatusCode()==408
                    || e.getStatusCode()==429 || e.getStatusCode()>=500));
            var error = new java.util.LinkedHashMap<String,Object>();
            error.put("code","downstream_error"); error.put("message",e.getMessage());
            error.put("tool",name); error.put("retryable",retryable);
            error.put("safeToRetry",retryable); error.put("ambiguousOutcome",write && "UNKNOWN".equals(state));
            error.put("retryAfterMs",retryable ? 5000 : 0);
            if(e.getStatusCode()!=null) error.put("downstreamStatus",e.getStatusCode());
            if(claim!=null) error.put("operation",finishFailure(principal,claim,state,error));
            return ResponseEntity.status(notDispatched?503:502).body(error);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            auditService.logInvocation(tool, actor, args, idempotencyKey,
                    "error", null, latency, e.getMessage());
            var error = new java.util.LinkedHashMap<String,Object>();
            error.put("code","internal_error"); error.put("message","Operation failed; check status before retrying.");
            error.put("retryable",!write); error.put("ambiguousOutcome",write);
            if(claim!=null) error.put("operation",finishFailure(principal,claim,"UNKNOWN",error));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    private Map<String,Object> finishFailure(String principal,Map<String,Object> claim,String state,Map<String,Object> error) {
        try { return idempotencyKeyService.complete(principal,claim,state,"RETRYABLE".equals(state)?503:502,error); }
        catch (Exception unavailable) {
            var result=operationView(claim); result.put("state","UNKNOWN"); result.put("ambiguousOutcome",true);
            result.put("retryable",false); result.put("safeToRetry",false); result.put("nextAction","POLL_STATUS"); return result;
        }
    }

    private static Map<String,Object> operationView(Map<String,Object> claim) {
        var result=new java.util.LinkedHashMap<>(claim);
        result.remove("leaseToken"); result.remove("dispatch"); result.remove("response"); return result;
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
