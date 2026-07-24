package site.yuqi.agent.intent;

/**
 * Strategy interface so {@link IntentOrchestrator} stays provider-agnostic.
 * Real implementations call an LLM; tests inject a mock that returns a
 * canned {@link IntentResult}.
 *
 * <p>Implementations MUST NOT execute tools, query the database, or have
 * any side effects. The classifier produces an intent label + extracted
 * entities; everything else is enforced downstream.
 */
public interface IntentClassifier {

    /**
     * Classify a single user utterance.
     *
     * @return the parsed, untrusted intent result
     * @throws IntentClassificationException on transport, parsing, or
     *         allowlist failures
     */
    IntentResult classify(IntentRequest request) throws IntentClassificationException;

    /**
     * Optional escalation hook: a second pass with a stronger model when the
     * first classification is below the confidence-clarify threshold but
     * still potentially salvageable. Implementations may return the same
     * result if escalation is disabled.
     */
    default IntentResult escalate(IntentRequest request, IntentResult firstPass)
            throws IntentClassificationException {
        return firstPass;
    }

    /**
     * Re-evaluate a structurally valid but potentially over-broad route.
     *
     * <p>This hook is intentionally semantic: provider implementations ask
     * the model to adjudicate the original request against the route
     * contract. Runtime code must not infer the route from user-text
     * keywords.
     */
    default IntentResult reviewRoute(IntentRequest request, IntentResult firstPass)
            throws IntentClassificationException {
        return firstPass;
    }
}
