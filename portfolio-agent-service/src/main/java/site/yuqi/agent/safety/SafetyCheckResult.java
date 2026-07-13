package site.yuqi.agent.safety;

import lombok.Builder;

import java.util.List;

@Builder
public record SafetyCheckResult(
        SafetyVerdict verdict,
        String checkType,
        String reason,
        String category,
        double confidence,
        List<String> constraints
) {
    public SafetyCheckResult {
        verdict = verdict == null ? SafetyVerdict.WARN : verdict;
        checkType = checkType == null || checkType.isBlank() ? "unknown" : checkType;
        category = category == null || category.isBlank() ? "UNKNOWN" : category;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }

    public boolean blocked() {
        return verdict == SafetyVerdict.BLOCK;
    }
}
