package site.yuqi.agent.service;

/**
 * Agent decision outcome — maps directly to the decision flowchart:
 *
 *   Agent → Can answer safely?
 *     → ANSWER: Yes, respond to user
 *     → CONFIRM: Need user confirmation for a tool action
 *     → HANDOFF: Escalate to human support
 *     → BLOCKED: Safety check blocked, return error/apology
 */
public enum AgentDecision {
    ANSWER,
    CONFIRM,
    HANDOFF,
    BLOCKED
}
