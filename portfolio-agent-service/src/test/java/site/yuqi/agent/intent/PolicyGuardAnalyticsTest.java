package site.yuqi.agent.intent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyGuardAnalyticsTest {

    private final PolicyGuard guard = new PolicyGuard();
    private final ToolDefinition analyticsTool = new ToolDefinition(
            "analytics.get_visitor_summary",
            IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
            "Get aggregate visitor metrics.",
            RiskLevel.READ_ONLY,
            true,
            Set.of(),
            Set.of("startDate", "endDate", "dimensions")
    );

    @Test
    void anonymousCannotRunAnalyticsTools() {
        PolicyGuard.PolicyDecision decision = guard.check(
                analyticsTool,
                Map.of("startDate", "2026-06-30", "endDate", "2026-07-06"),
                IntentRequest.builder().sessionId("s1").utterance("recent visitors").build());

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.getReason()).contains("VIEWER");
    }

    @Test
    void viewerCanStageAnalyticsConfirmation() {
        PolicyGuard.PolicyDecision decision = guard.check(
                analyticsTool,
                Map.of("startDate", "2026-06-30", "endDate", "2026-07-06"),
                IntentRequest.builder()
                        .sessionId("s1")
                        .utterance("recent visitors")
                        .userRoles("VIEWER")
                        .build());

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.isRequiresConfirmation()).isTrue();
    }
}
