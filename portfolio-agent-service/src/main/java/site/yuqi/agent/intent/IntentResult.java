package site.yuqi.agent.intent;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Untrusted LLM output, parsed into a strongly-typed record. Every field
 * must be re-validated by {@link IntentValidator} before any backend call is
 * made.
 *
 * @param intent                the classified intent label
 * @param targetTool            the chosen tool name; MUST exist in
 *                              {@link ToolRegistry} after validation
 * @param confidence            classifier-reported confidence in [0,1]
 * @param language              ISO 639-1 code, preserved from user input
 * @param normalizedQuery       English paraphrase suitable for internal
 *                              search (nullable)
 * @param entities              extracted entities; values are model-provided
 *                              strings/numbers/lists — IDs are NEVER trusted
 *                              and must come from {@link EntityResolver}
 * @param riskLevel             must match the tool's declared risk level
 *                              (unless intent is CLARIFICATION_NEEDED)
 * @param requiresConfirmation  must be true for any non-READ_ONLY tool
 * @param missingEntities       names of required entities the LLM could not
 *                              extract — drives clarification responses
 * @param clarificationQuestion human-readable follow-up question (nullable)
 * @param responsePolicy         LLM-selected answer policy; code propagates it
 *                               but does not infer it from user keywords
 * @param responseConstraints    LLM-selected constraints for generation
 * @param generationTier         allowlisted model tier selected by the planner
 * @param progressMessage        short user-facing progress text in the input language
 */
public record IntentResult(
        IntentType intent,
        String targetTool,
        double confidence,
        String language,
        String normalizedQuery,
        Map<String, Object> entities,
        RiskLevel riskLevel,
        boolean requiresConfirmation,
        List<String> missingEntities,
        String clarificationQuestion,
        String responsePolicy,
        List<String> responseConstraints,
        GenerationTier generationTier,
        String progressMessage
) {
    private static final Set<String> ALLOWED_RESPONSE_POLICIES = Set.of(
            "STANDARD", "GROUNDED", "PUBLIC_ESTIMATE", "RESTRICTED");
    private static final Set<String> ALLOWED_RESPONSE_CONSTRAINTS = Set.of(
            "PUBLIC_CONTEXT_ONLY", "LABEL_AS_ESTIMATE", "STATE_ASSUMPTIONS",
            "NO_PRIVATE_RECORD_CLAIM", "AGGREGATE_ONLY", "CITE_GROUNDING",
            "ASK_IF_CONTEXT_INSUFFICIENT");

    public IntentResult(IntentType intent,
                        String targetTool,
                        double confidence,
                        String language,
                        String normalizedQuery,
                        Map<String, Object> entities,
                        RiskLevel riskLevel,
                        boolean requiresConfirmation,
                        List<String> missingEntities,
                        String clarificationQuestion) {
        this(intent, targetTool, confidence, language, normalizedQuery, entities, riskLevel,
                requiresConfirmation, missingEntities, clarificationQuestion,
                "STANDARD", List.of(), GenerationTier.STANDARD, null);
    }

    public IntentResult(IntentType intent,
                        String targetTool,
                        double confidence,
                        String language,
                        String normalizedQuery,
                        Map<String, Object> entities,
                        RiskLevel riskLevel,
                        boolean requiresConfirmation,
                        List<String> missingEntities,
                        String clarificationQuestion,
                        String responsePolicy,
                        List<String> responseConstraints,
                        String progressMessage) {
        this(intent, targetTool, confidence, language, normalizedQuery, entities, riskLevel,
                requiresConfirmation, missingEntities, clarificationQuestion,
                responsePolicy, responseConstraints, GenerationTier.STANDARD, progressMessage);
    }

    public IntentResult {
        if (entities == null) entities = Map.of();
        if (missingEntities == null) missingEntities = List.of();
        String normalizedPolicy = responsePolicy == null
                ? "STANDARD"
                : responsePolicy.trim().toUpperCase(Locale.ROOT);
        responsePolicy = ALLOWED_RESPONSE_POLICIES.contains(normalizedPolicy)
                ? normalizedPolicy
                : "STANDARD";
        responseConstraints = responseConstraints == null
                ? List.of()
                : responseConstraints.stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.trim().toUpperCase(Locale.ROOT))
                        .filter(ALLOWED_RESPONSE_CONSTRAINTS::contains)
                        .distinct()
                        .limit(8)
                        .toList();
        if (generationTier == null) generationTier = GenerationTier.STANDARD;
        if (progressMessage != null) {
            progressMessage = progressMessage.replace('\n', ' ').replace('\r', ' ').trim();
            if (progressMessage.length() > 160) {
                progressMessage = progressMessage.substring(0, 160);
            }
        }
    }
}
