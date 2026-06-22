package site.yuqi.agent.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * A staged write operation awaiting explicit user confirmation.
 *
 * <p>The first turn of a write tool produces a {@link PendingAction} and
 * returns {@code CONFIRMATION_REQUIRED} to the caller. The caller's second
 * turn submits the {@code pendingActionId} and the orchestrator executes
 * the previously-validated, previously-resolved action verbatim — the LLM
 * is NOT consulted again on the second leg.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAction {

    private String id;
    private String sessionId;
    private String userId;
    private String toolName;
    private IntentType intent;
    private RiskLevel riskLevel;
    private Map<String, Object> resolvedArguments;
    private String previewMessage;
    private Instant createdAt;
    private Instant expiresAt;
}
