package site.yuqi.agent.generation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import site.yuqi.agent.intent.IntentClassificationException;
import site.yuqi.agent.intent.IntentClassifier;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.intent.IntentType;
import site.yuqi.agent.intent.IntentValidator;

/**
 * LLM-owned route planner for the streaming agent endpoint.
 *
 * <p>The planner does not inspect user text with keyword rules. It asks the
 * configured intent classifier for a structured decision and only maps the
 * returned enum/tool shape into the runtime branch that should execute.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAgentRoutePlanner {

    private final IntentClassifier classifier;
    private final IntentValidator validator;
    private final AgentRouteStrategies strategies;

    @Value("${agent.intent.pending-action.decision-confidence:0.80}")
    private double pendingDecisionConfidence;

    @Value("${agent.intent.review-general-chat:true}")
    private boolean reviewGeneralChat;

    public AgentRouteDecision plan(IntentRequest request) {
        IntentResult intent = classifier.classify(request);
        IntentValidator.ValidationResult validation = validator.validate(intent);

        if (!IntentValidator.hasValidEnvelope(intent) || validation.getStatus() == IntentValidator.Status.REJECT) {
            return strategies.select(intent, validation);
        }

        if (validation.getStatus() == IntentValidator.Status.CLARIFY
                && intent.intent() != IntentType.CLARIFICATION_NEEDED
                && intent.intent() != IntentType.UNKNOWN
                && intent.targetTool() != null) {
            IntentResult escalated = classifier.escalate(request, intent);
            if (escalated != intent) {
                intent = escalated;
                validation = validator.validate(intent);
            }
        }

        if (!IntentValidator.hasValidEnvelope(intent)) {
            return strategies.select(intent, validation);
        }
        if (reviewGeneralChat && (intent.intent() == IntentType.GENERAL_CHAT || intent.intent() == IntentType.WEB_GUIDE)
                && validation.getStatus() != IntentValidator.Status.REJECT) {
            try {
                IntentResult reviewed = classifier.reviewRoute(request, intent);
                if (reviewed != intent) {
                    intent = reviewed;
                    validation = validator.validate(intent);
                }
            } catch (IntentClassificationException e) {
                log.debug("Semantic route review skipped: {}", e.toString());
            }
        }

        return strategies.select(intent, validation);
    }

    /**
     * Classifies a reply to a staged write without using language-specific
     * keyword rules. Only an explicit, high-confidence authorization or
     * cancellation is actionable; every other result preserves the pending
     * action and asks the user to clarify.
     */
    public PendingActionDecision planPendingAction(IntentRequest request) {
        IntentResult intent = classifier.classify(request);
        if (!IntentValidator.hasValidEnvelope(intent)) {
            return PendingActionDecision.clarify(intent, "Please clearly confirm or cancel the pending action.");
        }
        if (intent.confidence() < pendingDecisionConfidence) {
            return PendingActionDecision.clarify(intent,
                    nonBlank(intent.clarificationQuestion(),
                            "Please clearly confirm or cancel the pending action."));
        }
        return switch (intent.intent()) {
            case PENDING_ACTION_CONFIRM -> PendingActionDecision.confirm(intent);
            case PENDING_ACTION_CANCEL -> PendingActionDecision.cancel(intent);
            case PENDING_ACTION_CLARIFY, CLARIFICATION_NEEDED, UNKNOWN ->
                    PendingActionDecision.clarify(intent,
                            nonBlank(intent.clarificationQuestion(),
                                    "Please clearly confirm or cancel the pending action."));
            default -> PendingActionDecision.clarify(intent,
                    nonBlank(intent.clarificationQuestion(),
                            "A pending action still needs your confirmation or cancellation."));
        };
    }

    public AgentRouteDecision classificationError(IntentClassificationException e) {
        log.warn("Intent classification unavailable: {}", e.toString());
        return AgentRouteDecision.clarify(null, "I could not interpret that request right now. Please try again.");
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public enum PendingActionDecisionType { CONFIRM, CANCEL, CLARIFY }

    public record PendingActionDecision(
            PendingActionDecisionType type,
            IntentResult intent,
            String message) {

        static PendingActionDecision confirm(IntentResult intent) {
            return new PendingActionDecision(PendingActionDecisionType.CONFIRM, intent, null);
        }

        static PendingActionDecision cancel(IntentResult intent) {
            return new PendingActionDecision(PendingActionDecisionType.CANCEL, intent, null);
        }

        static PendingActionDecision clarify(IntentResult intent, String message) {
            return new PendingActionDecision(PendingActionDecisionType.CLARIFY, intent, message);
        }
    }
}
