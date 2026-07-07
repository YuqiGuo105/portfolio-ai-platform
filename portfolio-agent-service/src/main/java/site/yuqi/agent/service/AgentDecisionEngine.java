package site.yuqi.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import site.yuqi.agent.handoff.HandoffReason;
import site.yuqi.agent.handoff.HandoffService;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.intent.RiskLevel;
import site.yuqi.agent.safety.SafetyCheckResult;
import site.yuqi.agent.safety.SafetyService;
import site.yuqi.agent.safety.SafetyVerdict;

import java.util.Set;
import java.util.UUID;

/**
 * Agent Decision Engine — implements the decision flowchart:
 *
 * <pre>
 * User message
 *   → Input safety check
 *     → BLOCK → return blocked / handoff
 *   → Intent classification
 *     → confidence < threshold → handoff (LOW_CONFIDENCE)
 *   → Tool needed?
 *     → risk = HIGH/CRITICAL → handoff (HIGH_RISK_ACTION)
 *     → risk = MEDIUM (write) → CONFIRM (ask user)
 *     → risk = LOW → execute
 *   → Generate answer
 *   → Output safety check
 *     → BLOCK → handoff (SAFETY_BLOCKED)
 *   → Return answer
 * </pre>
 *
 * Also detects explicit user handoff requests ("talk to human", "speak to agent").
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDecisionEngine {

    private final SafetyService safetyService;
    private final HandoffService handoffService;

    @Value("${safety.handoff-confidence-threshold:0.4}")
    private double handoffConfidenceThreshold;

    private static final Set<String> HUMAN_REQUEST_KEYWORDS = Set.of(
            "talk to human", "speak to agent", "human support",
            "real person", "transfer to agent", "connect me to support"
    );

    /**
     * Evaluate input safety — first gate in the pipeline.
     */
    public DecisionResult evaluateInput(String userMessage, UUID runId) {
        // Check if user explicitly requests human
        if (isHumanRequest(userMessage)) {
            return new DecisionResult(AgentDecision.HANDOFF, HandoffReason.USER_REQUESTED,
                    "User explicitly requested human support");
        }

        SafetyCheckResult inputCheck = safetyService.checkInput(userMessage, runId);
        if (inputCheck.blocked()) {
            return new DecisionResult(AgentDecision.BLOCKED, HandoffReason.SAFETY_BLOCKED,
                    inputCheck.reason());
        }

        return new DecisionResult(AgentDecision.ANSWER, null, null);
    }

    /**
     * Evaluate intent confidence — second gate after classification.
     */
    public DecisionResult evaluateIntent(IntentResult intent, UUID runId) {
        if (intent.confidence() < handoffConfidenceThreshold) {
            return new DecisionResult(AgentDecision.HANDOFF, HandoffReason.LOW_CONFIDENCE,
                    "Intent confidence %.2f below threshold %.2f".formatted(
                            intent.confidence(), handoffConfidenceThreshold));
        }
        return new DecisionResult(AgentDecision.ANSWER, null, null);
    }

    /**
     * Evaluate tool risk — third gate before tool execution.
     */
    public DecisionResult evaluateToolRisk(String toolName, RiskLevel riskLevel, UUID runId) {
        if (riskLevel == RiskLevel.DESTRUCTIVE) {
            return new DecisionResult(AgentDecision.HANDOFF, HandoffReason.HIGH_RISK_ACTION,
                    "Tool %s has risk level %s — requires human review".formatted(toolName, riskLevel));
        }
        if (riskLevel == RiskLevel.RISKY_WRITE) {
            return new DecisionResult(AgentDecision.CONFIRM, null,
                    "Tool %s requires user confirmation".formatted(toolName));
        }
        return new DecisionResult(AgentDecision.ANSWER, null, null);
    }

    /**
     * Evaluate output safety — final gate before returning response.
     */
    public DecisionResult evaluateOutput(String aiResponse, UUID runId) {
        SafetyCheckResult outputCheck = safetyService.checkOutput(aiResponse, runId);
        if (outputCheck.blocked()) {
            return new DecisionResult(AgentDecision.BLOCKED, HandoffReason.SAFETY_BLOCKED,
                    outputCheck.reason());
        }
        return new DecisionResult(AgentDecision.ANSWER, null, null);
    }

    /**
     * Execute handoff — create ticket and notify CRM.
     */
    public UUID executeHandoff(UUID conversationId, UUID runId, String userId,
                               HandoffReason reason, String summary) {
        return handoffService.createHandoff(conversationId, runId, userId, reason, summary);
    }

    private boolean isHumanRequest(String message) {
        String lower = message.toLowerCase().trim();
        return HUMAN_REQUEST_KEYWORDS.stream().anyMatch(lower::contains);
    }

    public record DecisionResult(AgentDecision decision, HandoffReason handoffReason, String reason) {
        public boolean shouldProceed() {
            return decision == AgentDecision.ANSWER;
        }
    }
}
