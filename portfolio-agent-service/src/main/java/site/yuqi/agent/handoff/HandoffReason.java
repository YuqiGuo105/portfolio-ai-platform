package site.yuqi.agent.handoff;

/**
 * Reasons an agent run may be handed off to human support.
 */
public enum HandoffReason {
    /** Agent confidence too low to answer safely */
    LOW_CONFIDENCE,
    /** Tool action is too high-risk for automated execution */
    HIGH_RISK_ACTION,
    /** Safety check blocked the response */
    SAFETY_BLOCKED,
    /** User explicitly requested human help */
    USER_REQUESTED,
    /** Repeated failed attempts to resolve */
    REPEATED_FAILURE
}
