package site.yuqi.mcp.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.mcp.model.RiskLevel;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsPrivacyPolicyTest {

    private AnalyticsPrivacyPolicy policy;
    private ToolDefinition tool;

    @BeforeEach
    void setUp() {
        policy = new AnalyticsPrivacyPolicy();
        ReflectionTestUtils.setField(policy, "minWindowDays", 7L);
        tool = ToolDefinition.builder()
                .name("analytics.get_visitor_summary")
                .mode(ToolMode.READ)
                .riskLevel(RiskLevel.MEDIUM)
                .build();
    }

    @Test
    void rejectsAnalyticsWithoutExplicitTimeRangeConfirmation() {
        AnalyticsPrivacyPolicy.Outcome outcome = policy.check(tool, Map.of(
                "startDate", "2026-07-01",
                "endDate", "2026-07-07"));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).contains("confirmation");
    }

    @Test
    void rejectsWindowShorterThanSevenDays() {
        AnalyticsPrivacyPolicy.Outcome outcome = policy.check(tool, Map.of(
                "_confirmedTimeRange", true,
                "startDate", "2026-07-06",
                "endDate", "2026-07-06"));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).contains("at least 7 days");
    }

    @Test
    void rejectsVisitorSpecificParameter() {
        AnalyticsPrivacyPolicy.Outcome outcome = policy.check(tool, Map.of(
                "_confirmedTimeRange", true,
                "startDate", "2026-07-01",
                "endDate", "2026-07-07",
                "visitorId", "v_123"));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).contains("visitor-specific");
    }

    @Test
    void acceptsConfirmedAggregateWindow() {
        AnalyticsPrivacyPolicy.Outcome outcome = policy.check(tool, Map.of(
                "_confirmedTimeRange", true,
                "startDate", "2026-07-01",
                "endDate", "2026-07-07"));

        assertThat(outcome.allowed()).isTrue();
    }

    @Test
    void acceptsPrivacyApprovedAggregateDimensions() {
        AnalyticsPrivacyPolicy.Outcome outcome = policy.check(tool, Map.of(
                "_confirmedTimeRange", true,
                "startDate", "2026-07-01",
                "endDate", "2026-07-07",
                "dimensions", List.of("city", "deviceCategory")));

        assertThat(outcome.allowed()).isTrue();
    }

    @Test
    void rejectsRawOrVisitorLevelDimensions() {
        AnalyticsPrivacyPolicy.Outcome outcome = policy.check(tool, Map.of(
                "_confirmedTimeRange", true,
                "startDate", "2026-07-01",
                "endDate", "2026-07-07",
                "dimensions", List.of("userAgent")));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).contains("dimension");
    }
}
