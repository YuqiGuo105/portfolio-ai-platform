package site.yuqi.agent.generation;

import lombok.RequiredArgsConstructor;
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
@Service
@RequiredArgsConstructor
public class LlmAgentRoutePlanner {

    private final IntentClassifier classifier;
    private final IntentValidator validator;

    public AgentRouteDecision plan(IntentRequest request) {
        IntentResult intent = classifier.classify(request);
        IntentValidator.ValidationResult validation = validator.validate(intent);

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

        return switch (intent.intent()) {
            case KNOWLEDGE_QA -> AgentRouteDecision.knowledge(intent);
            case HANDOFF_REQUESTED -> AgentRouteDecision.handoff(intent,
                    nonBlank(intent.clarificationQuestion(),
                            "I can connect you with a human support agent. Please confirm and provide an email for follow-up."));
            case GENERAL_CHAT -> AgentRouteDecision.generalChat(intent,
                    nonBlank(intent.clarificationQuestion(),
                            "I can help with Yuqi's portfolio, site analytics, content operations, and support workflows."));
            case UNKNOWN, CLARIFICATION_NEEDED -> AgentRouteDecision.clarify(intent,
                    nonBlank(validation.getMessage(),
                            nonBlank(intent.clarificationQuestion(), "Could you clarify what you need?")));
            default -> switch (validation.getStatus()) {
                case EXECUTE -> AgentRouteDecision.tool(intent);
                case GENERAL_CHAT -> AgentRouteDecision.generalChat(intent,
                        "I can help with Yuqi's portfolio, site analytics, content operations, and support workflows.");
                case CLARIFY -> AgentRouteDecision.clarify(intent,
                        nonBlank(validation.getMessage(), "Could you clarify what you need?"));
                case REJECT -> AgentRouteDecision.clarify(intent,
                        nonBlank(validation.getMessage(), "I could not safely route that request."));
            };
        };
    }

    public AgentRouteDecision classificationError(IntentClassificationException e) {
        return AgentRouteDecision.clarify(null, "I could not route that request safely: " + e.getMessage());
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
