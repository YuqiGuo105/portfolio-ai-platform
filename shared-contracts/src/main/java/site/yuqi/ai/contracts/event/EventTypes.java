package site.yuqi.ai.contracts.event;

/**
 * Event type constants — map to OpenSearch log indexes.
 */
public final class EventTypes {
    private EventTypes() {}

    // Agent lifecycle
    public static final String AGENT_RUN_STARTED = "agent_run.started";
    public static final String AGENT_RUN_COMPLETED = "agent_run.completed";
    public static final String AGENT_STEP_COMPLETED = "agent_step.completed";

    // Model calls
    public static final String MODEL_CALL_COMPLETED = "model_call.completed";

    // Tool calls
    public static final String TOOL_CALL_COMPLETED = "tool_call.completed";

    // Retrieval
    public static final String RETRIEVAL_COMPLETED = "retrieval.completed";

    // Safety
    public static final String SAFETY_CHECK_COMPLETED = "safety.check_completed";

    // Answer
    public static final String ANSWER_GENERATED = "answer.generated";
    public static final String ANSWER_BLOCKED = "answer.blocked";

    // Handoff
    public static final String HANDOFF_CREATED = "handoff.created";

    // Feedback
    public static final String FEEDBACK_SUBMITTED = "feedback.submitted";
}
