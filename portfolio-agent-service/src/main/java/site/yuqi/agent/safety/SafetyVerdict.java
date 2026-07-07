package site.yuqi.agent.safety;

/**
 * Safety check verdict — determines whether the agent can proceed.
 */
public enum SafetyVerdict {
    /** Safe to proceed */
    PASS,
    /** Borderline — proceed with caution, log warning */
    WARN,
    /** Blocked — do not answer, may trigger handoff */
    BLOCK
}
