package site.yuqi.agent.generation;

import org.springframework.stereotype.Component;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.intent.IntentType;
import site.yuqi.agent.intent.IntentValidator;

import java.util.Map;
import java.util.function.Function;

/** Compiles an untrusted semantic decision into an allow-listed runtime route. */
@Component
public class AgentRouteStrategies {

    private final Map<IntentType, Function<IntentResult, AgentRouteDecision>> responseStrategies = Map.of(
            IntentType.KNOWLEDGE_QA, AgentRouteDecision::knowledge,
            IntentType.GENERAL_CHAT, intent -> AgentRouteDecision.generalChat(intent, null),
            IntentType.WEB_GUIDE, AgentRouteDecision::webGuide);

    public AgentRouteDecision select(IntentResult intent, IntentValidator.ValidationResult validation) {
        if (intent == null || intent.intent() == null || validation == null
                || validation.getStatus() == null
                || validation.getStatus() == IntentValidator.Status.REJECT) {
            return AgentRouteDecision.clarify(intent, "I could not safely interpret that request. Please rephrase it.");
        }

        // A handoff route only stages confirmation; it does not contact a human.
        if (intent.intent() == IntentType.HANDOFF_REQUESTED) {
            return AgentRouteDecision.handoff(intent, message(validation.getMessage(),
                    "I can connect you with a human support agent. Please confirm and provide an email for follow-up."));
        }
        if (validation.getStatus() == IntentValidator.Status.CLARIFY) {
            return AgentRouteDecision.clarify(intent, message(validation.getMessage(), "Could you clarify what you need?"));
        }
        Function<IntentResult, AgentRouteDecision> strategy = responseStrategies.get(intent.intent());
        if (strategy != null && validation.getStatus() == IntentValidator.Status.GENERAL_CHAT) {
            return strategy.apply(intent);
        }
        if (strategy == null && validation.getStatus() == IntentValidator.Status.EXECUTE
                && validation.getTool() != null
                && validation.getTool().intent() == intent.intent()
                && validation.getTool().name().equals(intent.targetTool())) {
            // Authorization and confirmation still run in IntentOrchestrator/PolicyGuard.
            return AgentRouteDecision.tool(intent);
        }
        return AgentRouteDecision.clarify(intent, "I could not safely route that request. Please clarify what you need.");
    }

    private static String message(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
