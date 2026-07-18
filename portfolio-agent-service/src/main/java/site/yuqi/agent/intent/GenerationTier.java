package site.yuqi.agent.intent;

import java.util.Locale;

/** Allowlisted model tier selected by the LLM planner. */
public enum GenerationTier {
    STANDARD,
    DEEP;

    public static GenerationTier fromModelValue(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STANDARD;
        }
    }
}
