package site.yuqi.agent.budget;

import java.math.BigDecimal;
import java.time.Instant;

public record BudgetDecision(
        boolean allowed,
        String reason,
        BigDecimal limitUsd,
        BigDecimal usedUsd,
        BigDecimal remainingUsd,
        BigDecimal reservedUsd,
        Instant resetAt) {

    public static BudgetDecision allowed(BigDecimal limitUsd,
                                         BigDecimal usedUsd,
                                         BigDecimal remainingUsd,
                                         BigDecimal reservedUsd,
                                         Instant resetAt) {
        return new BudgetDecision(true, null, limitUsd, usedUsd, remainingUsd, reservedUsd, resetAt);
    }

    public static BudgetDecision denied(String reason,
                                        BigDecimal limitUsd,
                                        BigDecimal usedUsd,
                                        BigDecimal remainingUsd,
                                        BigDecimal reservedUsd,
                                        Instant resetAt) {
        return new BudgetDecision(false, reason, limitUsd, usedUsd, remainingUsd, reservedUsd, resetAt);
    }
}
