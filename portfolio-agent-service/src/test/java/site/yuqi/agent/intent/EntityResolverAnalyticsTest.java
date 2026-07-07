package site.yuqi.agent.intent;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EntityResolverAnalyticsTest {

    private final EntityResolver resolver = new EntityResolver(null);
    private final ToolDefinition tool = new ToolDefinition(
            "analytics.get_visitor_summary",
            IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
            "Get aggregate visitor metrics.",
            RiskLevel.READ_ONLY,
            true,
            Set.of(),
            Set.of("startDate", "endDate", "timeRangePreset")
    );

    @Test
    void defaultsMissingAnalyticsRangeToLastSevenDays() {
        IntentResult intent = new IntentResult(
                IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
                "analytics.get_visitor_summary",
                0.95,
                "en",
                "recent visitors",
                Map.of("timeRangePreset", "recent"),
                RiskLevel.READ_ONLY,
                true,
                List.of(),
                null);

        EntityResolver.EntityResolutionResult result = resolver.resolve(intent, tool, new IntentRequest());

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        assertThat(result.isReady()).isTrue();
        assertThat(result.getResolvedArguments())
                .containsEntry("startDate", today.minusDays(6).toString())
                .containsEntry("endDate", today.toString())
                .containsEntry("_timeRangeDefaulted", true);
    }

    @Test
    void expandsShortAnalyticsRangeToSevenDays() {
        IntentResult intent = new IntentResult(
                IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
                "analytics.get_visitor_summary",
                0.95,
                "en",
                "today visitors",
                Map.of("startDate", "2026-07-06", "endDate", "2026-07-06"),
                RiskLevel.READ_ONLY,
                true,
                List.of(),
                null);

        EntityResolver.EntityResolutionResult result = resolver.resolve(intent, tool, new IntentRequest());

        assertThat(result.isReady()).isTrue();
        assertThat(result.getResolvedArguments())
                .containsEntry("startDate", "2026-06-30")
                .containsEntry("endDate", "2026-07-06")
                .containsEntry("_privacyWindowAdjusted", true);
    }
}
