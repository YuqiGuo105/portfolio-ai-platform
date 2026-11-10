package site.yuqi.agent.guide;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class BrowserActionPlanTest {
    @Test void acceptsTypedReadOnlyPlansAndRemovesExtraFields() {
        var plan = BrowserActionPlan.parse(Map.of("guideStartMode", "START_NOW", "browserActions", List.of(
                Map.of("type", "diagram_step", "flow", "publish-content", "step", 3, "script", "secret"),
                Map.of("type", "terminal_command", "command", "pwd"))));
        assertThat(plan.get("autoStart")).isEqualTo(true);
        assertThat(plan.toString()).doesNotContain("script", "secret");
    }

    @Test void rejectsWholePlanForUnsafeOrMalformedActions() {
        for (var action : List.of(
                Map.of("type", "terminal_command", "command", "pwd; curl bad"),
                Map.of("type", "terminal_command"),
                Map.of("type", "open_project", "projectId", "../admin"),
                Map.of("type", "diagram_step", "flow", "publish-content", "step", 1.5),
                Map.of("type", "globe_city", "city", "Tokyo", "lat", Double.NaN, "lng", 139),
                Map.of("type", "eval"))) {
            assertThatIllegalArgumentException().isThrownBy(() -> BrowserActionPlan.parse(Map.of("browserActions", List.of(action))));
        }
    }

    @Test void regularToursAreUnaffectedAndProposalsDoNotAutostartByDefault() {
        assertThat(BrowserActionPlan.parse(Map.of("guideTargetKeys", List.of("home.about")))).isNull();
        assertThat(BrowserActionPlan.parse(Map.of("browserActions", List.of(Map.of("type", "terminal_command", "command", "help"))))
                .get("autoStart")).isEqualTo(false);
    }
}
