package site.yuqi.agent.intent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Single-turn input to {@link IntentOrchestrator}. Carries the user's raw
 * utterance plus enough identity to drive {@link PolicyGuard}.
 *
 * <p>{@code pendingActionId} + {@code confirm} are used on the second leg of
 * a write-tool confirmation flow.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentRequest {

    @NotBlank
    private String sessionId;

    /** Caller identity. Null = anonymous (public read-only tools only). */
    private String userId;

    /** Optional caller email — passed through to AuditService. */
    private String userEmail;

    /**
     * Comma-separated permission roles ({@code VIEWER,EDITOR,PUBLISHER,ADMIN}).
     * Sourced upstream from the authenticated session.
     */
    private String userRoles;

    @NotBlank
    @Size(max = 8_000)
    private String utterance;

    /** Front-end / page context the LLM may use as a hint. */
    private Map<String, Object> pageContext;

    /** Set on confirmation leg of a previously staged pending action. */
    private String pendingActionId;

    /** Set on confirmation leg: explicit user decision. */
    private Boolean confirm;
}
