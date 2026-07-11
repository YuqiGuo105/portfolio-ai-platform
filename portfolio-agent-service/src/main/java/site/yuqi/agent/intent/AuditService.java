package site.yuqi.agent.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Structured audit log for the intent pipeline.
 *
 * <p>Sprint 1 writes JSON lines to SLF4J at INFO level — Cloud Logging
 * captures them automatically on Cloud Run. A future iteration can swap in
 * a Supabase writer behind this same method (see
 * {@code create table mcp_tool_audit_logs (...)} in the README).
 *
 * <p>Sensitive fields are redacted defensively before logging.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final Set<String> REDACT_KEYS = Set.of(
            "apiKey", "api_key", "authorization", "token", "secret", "password",
            "serviceRoleKey", "service_role_key",
            "openaiApiKey", "openai_api_key",
            "geminiApiKey", "gemini_api_key", "otp", "verificationCode",
            "verification_code", "codeHash", "code_hash");

    private final ObjectMapper objectMapper;

    public void logClassification(IntentRequest req, IntentResult result) {
        write("intent.classify", "ok", req, Map.of(
                "intent", result.intent().name(),
                "targetTool", String.valueOf(result.targetTool()),
                "confidence", result.confidence(),
                "language", String.valueOf(result.language()),
                "missingEntities", result.missingEntities()
        ), null);
    }

    public void logValidationFailure(IntentRequest req, IntentResult result, String reason) {
        write("intent.validate", "rejected", req, Map.of(
                "intent", result == null ? "null" : result.intent().name(),
                "targetTool", result == null ? "null" : String.valueOf(result.targetTool()),
                "reason", reason
        ), null);
    }

    public void logClarification(IntentRequest req, IntentResult result, String question) {
        write("intent.clarify", "asked", req, Map.of(
                "intent", result == null ? "null" : result.intent().name(),
                "targetTool", result == null ? "null" : String.valueOf(result.targetTool()),
                "question", question
        ), null);
    }

    public void logConfirmationStaged(IntentRequest req, PendingAction action) {
        write("intent.confirmation_required", "staged", req, Map.of(
                "pendingActionId", action.getId(),
                "tool", action.getToolName(),
                "intent", action.getIntent().name(),
                "riskLevel", action.getRiskLevel().name(),
                "args", redact(action.getResolvedArguments())
        ), null);
    }

    public void logExecutionStart(IntentRequest req, String tool, Map<String, Object> args) {
        write("tool.execute", "start", req, Map.of(
                "tool", tool,
                "args", redact(args)
        ), null);
    }

    public void logExecutionResult(IntentRequest req, String tool, boolean success,
                                   long latencyMs, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tool", tool);
        body.put("success", success);
        body.put("latencyMs", latencyMs);
        if (error != null) body.put("error", error);
        write("tool.execute", success ? "ok" : "failed", req, body, error);
    }

    public void logError(IntentRequest req, String stage, Throwable err) {
        write(stage, "error", req, Map.of(
                "errorClass", err.getClass().getSimpleName(),
                "errorMessage", String.valueOf(err.getMessage())
        ), err.getMessage());
    }

    // ── Internals ────────────────────────────────────────────────────────

    private void write(String stage, String status, IntentRequest req,
                       Map<String, Object> body, String error) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ts", Instant.now().toString());
        envelope.put("stage", stage);
        envelope.put("status", status);
        if (req != null) {
            envelope.put("sessionId", req.getSessionId());
            envelope.put("userId", req.getUserId());
        }
        envelope.put("body", body);
        if (error != null) envelope.put("error", error);
        try {
            log.info("AUDIT {}", objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            log.info("AUDIT {} (serialization failed: {})", envelope, e.toString());
        }
    }

    private Map<String, Object> redact(Map<String, Object> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : in.entrySet()) {
            if (REDACT_KEYS.contains(e.getKey())) {
                out.put(e.getKey(), "***");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
