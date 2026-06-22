package site.yuqi.agent.intent;

import java.util.List;
import java.util.Map;

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
        String clarificationQuestion
) {
    public IntentResult {
        if (entities == null) entities = Map.of();
        if (missingEntities == null) missingEntities = List.of();
    }
}
