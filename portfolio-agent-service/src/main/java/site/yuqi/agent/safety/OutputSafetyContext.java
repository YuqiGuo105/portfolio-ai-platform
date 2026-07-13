package site.yuqi.agent.safety;

import java.util.List;

/**
 * Context passed to the output safety classifier so it can distinguish
 * policy-compliant answers (e.g. public-estimate) from actual privacy violations.
 *
 * @param userMessage          the original user question
 * @param candidateResponse    the generated answer to classify
 * @param responsePolicy       LLM-selected policy (e.g. STANDARD, PUBLIC_ESTIMATE)
 * @param responseConstraints  LLM-selected constraints (e.g. LABEL_AS_ESTIMATE)
 */
public record OutputSafetyContext(
        String userMessage,
        String candidateResponse,
        String responsePolicy,
        List<String> responseConstraints
) {
    public OutputSafetyContext {
        if (responsePolicy == null) responsePolicy = "STANDARD";
        if (responseConstraints == null) responseConstraints = List.of();
    }
}
