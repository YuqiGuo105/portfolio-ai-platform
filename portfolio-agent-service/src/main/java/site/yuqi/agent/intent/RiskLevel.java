package site.yuqi.agent.intent;

/**
 * Tool risk classification. Drives confidence thresholds, confirmation
 * requirements, and the permission rules in {@link PolicyGuard}.
 *
 * <pre>
 *   READ_ONLY     – no side effects, e.g. search, get, list.
 *   SAFE_WRITE    – idempotent or low-blast write (e.g. retry job).
 *   RISKY_WRITE   – emits domain events, indexing, publishing.
 *   DESTRUCTIVE   – removes or hard-disables a subscriber/job/etc.
 * </pre>
 */
public enum RiskLevel {
    READ_ONLY,
    SAFE_WRITE,
    RISKY_WRITE,
    DESTRUCTIVE
}
