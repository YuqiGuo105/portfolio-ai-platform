package site.yuqi.agent.generation;

import site.yuqi.agent.intent.IntentResult;

public record AgentRouteDecision(
        AgentRoute route,
        IntentResult intent,
        String message) {

    public static AgentRouteDecision knowledge(IntentResult intent) {
        return new AgentRouteDecision(AgentRoute.KNOWLEDGE_QA, intent, null);
    }

    public static AgentRouteDecision tool(IntentResult intent) {
        return new AgentRouteDecision(AgentRoute.MCP_TOOL, intent, null);
    }

    public static AgentRouteDecision clarify(IntentResult intent, String message) {
        return new AgentRouteDecision(AgentRoute.CLARIFY, intent, message);
    }

    public static AgentRouteDecision handoff(IntentResult intent, String message) {
        return new AgentRouteDecision(AgentRoute.HANDOFF, intent, message);
    }

    public static AgentRouteDecision webGuide(IntentResult intent) {
        return new AgentRouteDecision(AgentRoute.WEB_GUIDE, intent, null);
    }

    public static AgentRouteDecision generalChat(IntentResult intent, String message) {
        return new AgentRouteDecision(AgentRoute.GENERAL_CHAT, intent, message);
    }
}
