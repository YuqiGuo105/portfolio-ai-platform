package site.yuqi.agent.intent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
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

    /** Server-derived Redis memory key. Do not trust or reuse raw sessionId for memory. */
    private String conversationId;

    /** Caller identity. Null = anonymous (public read-only tools only). */
    private String userId;

    /** Optional caller email — passed through to AuditService. */
    private String userEmail;

    /**
     * Comma-separated permission roles ({@code VIEWER,EDITOR,PUBLISHER,ADMIN}).
     *
     * <p><strong>Never trust the value the client sends.</strong> The
     * {@code SupabaseJwtAuthFilter} overwrites this field on every request
     * that arrives with a Supabase JWT or no bearer at all. It is only
     * honored as-supplied when the caller presented
     * {@code agent.auth.internal-token} — that path is reserved for the
     * Portfolio Next.js proxy, which has already Supabase-verified the end
     * user server-side and derived the roles authoritatively.
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

    /**
     * Optional recent conversation turns, newest last (max 6).
     * Each entry contains "role" ("user" or "assistant") and "content".
     * Classifiers use this to extract entities from earlier turns so that
     * follow-up messages like "send it" can resolve name/email/message
     * without requiring the user to repeat them.
     * Nullable and ignored when null — fully backward-compatible.
     */
    private List<Map<String, String>> recentMessages;

    /** Redis compact summary, structured as userGoals/keyFacts/entities/toolOutcomes/openQuestions. */
    private Map<String, Object> compactSummary;

    /** Redis extracted state used by the planner, for example resolved entities and open questions. */
    private Map<String, Object> structuredState;

    /** Redis pending action context, if a previous turn is waiting for confirmation. */
    private Map<String, Object> pendingActionContext;
}
