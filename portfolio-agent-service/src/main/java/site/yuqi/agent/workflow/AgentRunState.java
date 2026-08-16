package site.yuqi.agent.workflow;

public enum AgentRunState {
    RECEIVED,
    ADMITTED,
    GUARDING,
    PLANNING,
    WAITING_CONFIRMATION,
    RETRIEVING,
    EXECUTING_TOOL,
    GENERATING,
    FINALIZING,
    COMPLETED,
    BLOCKED,
    HANDOFF,
    FAILED
}
