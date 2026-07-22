package site.yuqi.agent.guide;

import org.junit.jupiter.api.Test;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.intent.IntentType;
import site.yuqi.agent.intent.RiskLevel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebGuidePlanServiceTest {

    private final WebGuidePlanService service = new WebGuidePlanService();

    @Test
    void filtersUnknownTargetsAndLocalizesCards() {
        IntentResult intent = new IntentResult(
                IntentType.WEB_GUIDE, null, 0.95, "zh-CN", null,
                Map.of(
                        "guideTargetKeys", List.of("home.projects", "javascript:alert(1)", "home.contact"),
                        "guideStartMode", "START_NOW",
                        "guideResponseMessage", "我来带你看看。"),
                RiskLevel.READ_ONLY, false, List.of(), null);

        WebGuidePlanService.WebGuidePlan plan = service.build(intent);

        assertThat(plan.language()).isEqualTo("zh");
        assertThat(plan.autoStart()).isTrue();
        assertThat(plan.steps()).extracting(step -> step.get("targetKey"))
                .containsExactly("home.projects", "home.contact");
        assertThat(plan.steps().get(0).get("title")).isEqualTo("项目作品");
    }

    @Test
    void fallsBackToFullCatalogWhenModelOmitsTargets() {
        IntentResult intent = new IntentResult(
                IntentType.WEB_GUIDE, null, 0.90, "en", null,
                Map.of("guideStartMode", "OFFER"),
                RiskLevel.READ_ONLY, false, List.of(), null);

        WebGuidePlanService.WebGuidePlan plan = service.build(intent);

        assertThat(plan.autoStart()).isFalse();
        assertThat(plan.steps()).hasSize(WebGuideCatalog.allowedKeys().size());
        assertThat(plan.controls()).containsEntry("start", "Start web guide");
    }
}
