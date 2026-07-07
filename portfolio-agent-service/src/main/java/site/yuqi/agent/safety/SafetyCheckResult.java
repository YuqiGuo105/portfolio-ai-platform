package site.yuqi.agent.safety;

import lombok.Builder;

@Builder
public record SafetyCheckResult(
        SafetyVerdict verdict,
        String checkType,
        String reason
) {
    public boolean blocked() {
        return verdict == SafetyVerdict.BLOCK;
    }
}
