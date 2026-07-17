package site.yuqi.agent.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Structural / safety validation of the untrusted {@link IntentResult}.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>{@link Status#EXECUTE}     – fully valid; orchestrator may proceed
 *       to entity resolution / policy.</li>
 *   <li>{@link Status#CLARIFY}     – ask the user (missing entities, low
 *       confidence, or CLARIFICATION_NEEDED).</li>
 *   <li>{@link Status#GENERAL_CHAT} – fall back to plain chat answer.</li>
 *   <li>{@link Status#REJECT}      – fatal mismatch (LLM lied about
 *       tool/intent/risk); orchestrator surfaces an error.</li>
 * </ul>
 */
@Slf4j
@Component
public class IntentValidator {

    public enum Status { EXECUTE, CLARIFY, GENERAL_CHAT, REJECT }

    private final ToolRegistry toolRegistry;

    @Value("${agent.intent.confidence.read-threshold:${MCP_INTENT_CONFIDENCE_READ_THRESHOLD:0.85}}")
    private double readThreshold;

    @Value("${agent.intent.confidence.clarify-threshold:${MCP_INTENT_CONFIDENCE_CLARIFY_THRESHOLD:0.65}}")
    private double clarifyThreshold;

    public IntentValidator(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public ValidationResult validate(IntentResult intent) {
        if (intent == null) {
            return ValidationResult.builder().status(Status.REJECT).message("Null intent.").build();
        }

        switch (intent.intent()) {
            case KNOWLEDGE_QA -> {
                return ValidationResult.builder().status(Status.GENERAL_CHAT).build();
            }
            case HANDOFF_REQUESTED -> {
                return ValidationResult.builder()
                        .status(Status.CLARIFY)
                        .message(nonBlank(intent.clarificationQuestion(),
                                "I can connect you with a human support agent. Please confirm."))
                        .build();
            }
            case GENERAL_CHAT -> {
                return ValidationResult.builder().status(Status.GENERAL_CHAT).build();
            }
            case UNKNOWN -> {
                return ValidationResult.builder()
                        .status(Status.CLARIFY)
                        .message(nonBlank(intent.clarificationQuestion(),
                                "I'm not sure how to help with that. Could you rephrase?"))
                        .build();
            }
            case CLARIFICATION_NEEDED -> {
                return ValidationResult.builder()
                        .status(Status.CLARIFY)
                        .message(nonBlank(intent.clarificationQuestion(),
                                "I need a bit more info to act on that."))
                        .missingEntities(intent.missingEntities())
                        .build();
            }
            case PENDING_ACTION_CONFIRM, PENDING_ACTION_CANCEL, PENDING_ACTION_CLARIFY -> {
                return ValidationResult.builder()
                        .status(Status.CLARIFY)
                        .message(nonBlank(intent.clarificationQuestion(),
                                "There is no pending action to resolve."))
                        .build();
            }
            default -> { /* fall through to tool-bound validation */ }
        }

        if (intent.targetTool() == null) {
            return ValidationResult.builder()
                    .status(Status.REJECT)
                    .message("Intent " + intent.intent() + " requires a targetTool but none was provided.")
                    .build();
        }

        ToolDefinition tool = toolRegistry.find(intent.targetTool()).orElse(null);
        if (tool == null) {
            return ValidationResult.builder()
                    .status(Status.REJECT)
                    .message("Tool not in allowlist: " + intent.targetTool())
                    .build();
        }

        if (tool.intent() != intent.intent()) {
            return ValidationResult.builder()
                    .status(Status.REJECT)
                    .message("Intent/tool mismatch: " + intent.intent() + " vs tool " + tool.name()
                            + " (expected intent " + tool.intent() + ").")
                    .build();
        }

        if (tool.riskLevel() != intent.riskLevel()) {
            return ValidationResult.builder()
                    .status(Status.REJECT)
                    .message("Risk-level mismatch for tool " + tool.name()
                            + ": expected " + tool.riskLevel() + ", got " + intent.riskLevel() + ".")
                    .build();
        }

        boolean selfServiceVerification = "subscription.request_unsubscribe_code".equals(tool.name())
                || "subscription.confirm_unsubscribe".equals(tool.name());
        if (tool.riskLevel() != RiskLevel.READ_ONLY
                && !selfServiceVerification
                && !intent.requiresConfirmation()) {
            return ValidationResult.builder()
                    .status(Status.REJECT)
                    .message("Non-read-only tool " + tool.name() + " must set requiresConfirmation=true.")
                    .build();
        }

        // Required entities present? Missing ones force clarification.
        List<String> missing = new ArrayList<>();
        for (String key : tool.requiredEntities()) {
            Object v = intent.entities().get(key);
            if (v == null || (v instanceof String s && s.isBlank())) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return ValidationResult.builder()
                    .status(Status.CLARIFY)
                    .message(nonBlank(intent.clarificationQuestion(),
                            "Missing required information: " + String.join(", ", missing)))
                    .missingEntities(missing)
                    .build();
        }

        // Confidence gating.
        if (tool.riskLevel() == RiskLevel.READ_ONLY) {
            if (intent.confidence() < clarifyThreshold) {
                return ValidationResult.builder()
                        .status(Status.CLARIFY)
                        .message("I'm not confident I understood that — could you clarify?")
                        .build();
            }
            if (intent.confidence() < readThreshold) {
                return ValidationResult.builder()
                        .status(Status.CLARIFY)
                        .message(nonBlank(intent.clarificationQuestion(),
                                "Did you want me to run " + tool.name() + "?"))
                        .build();
            }
        } else {
            // Writes ALWAYS require confirmation; no auto-execute regardless of confidence.
            if (intent.confidence() < clarifyThreshold) {
                return ValidationResult.builder()
                        .status(Status.CLARIFY)
                        .message("That looks like a write action but I'm not confident — please rephrase.")
                        .build();
            }
        }

        return ValidationResult.builder().status(Status.EXECUTE).tool(tool).build();
    }

    private static String nonBlank(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private Status status;
        private String message;
        private ToolDefinition tool;
        private List<String> missingEntities;

        public boolean isValid() { return status == Status.EXECUTE; }
    }
}
