package site.yuqi.agent.intent;

import java.util.Set;

/**
 * Static description of a backend tool the MCP layer can invoke. Owned and
 * vended by {@link ToolRegistry}. The LLM is constrained at prompt time to
 * pick {@link #name()} from this allowlist; runtime validation re-checks.
 *
 * @param name                 dot-cased tool id (e.g. {@code admin.search_content})
 * @param intent               the single intent this tool services
 * @param description          shown to the LLM in the prompt
 * @param riskLevel            drives confidence threshold + permissions
 * @param requiresConfirmation if true, ToolExecutor MUST stage a
 *                             {@link PendingAction} before invoking
 * @param requiredEntities     keys that MUST appear in
 *                             {@link IntentResult#entities()} (or
 *                             missingEntities) before execution
 * @param optionalEntities     additional entities the LLM may extract
 */
public record ToolDefinition(
        String name,
        IntentType intent,
        String description,
        RiskLevel riskLevel,
        boolean requiresConfirmation,
        Set<String> requiredEntities,
        Set<String> optionalEntities
) { }
