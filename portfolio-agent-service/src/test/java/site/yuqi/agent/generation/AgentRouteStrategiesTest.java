package site.yuqi.agent.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.agent.intent.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRouteStrategiesTest {
    private final AgentRouteStrategies strategies = new AgentRouteStrategies();
    private IntentValidator validator;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.invokeMethod(registry, "init");
        validator = new IntentValidator(registry);
        ReflectionTestUtils.setField(validator, "readThreshold", 0.85);
        ReflectionTestUtils.setField(validator, "clarifyThreshold", 0.65);
    }

    @Test
    void selectsEachResponseStrategyFromValidatedSemanticIntent() {
        Map<IntentType, AgentRoute> expected = Map.of(
                IntentType.GENERAL_CHAT, AgentRoute.GENERAL_CHAT,
                IntentType.KNOWLEDGE_QA, AgentRoute.KNOWLEDGE_QA,
                IntentType.WEB_GUIDE, AgentRoute.WEB_GUIDE,
                IntentType.HANDOFF_REQUESTED, AgentRoute.HANDOFF);
        expected.forEach((type, route) -> {
            IntentResult intent = intent(type, null, RiskLevel.READ_ONLY, false, Map.of());
            assertThat(strategies.select(intent, validator.validate(intent)).route()).isEqualTo(route);
        });
    }

    @Test
    void rejectTakesPrecedenceForEveryIntent() {
        for (IntentType type : IntentType.values()) {
            IntentResult intent = intent(type, null, RiskLevel.READ_ONLY, false, Map.of());
            assertThat(strategies.select(intent, IntentValidator.ValidationResult.builder()
                    .status(IntentValidator.Status.REJECT).build()).route()).isEqualTo(AgentRoute.CLARIFY);
        }
    }

    @Test
    void validatedReadAndConfirmedWriteRouteThroughTheExistingOrchestrator() {
        for (IntentResult intent : List.of(
                intent(IntentType.ADMIN_SEARCH_CONTENT, "admin.search_content", RiskLevel.READ_ONLY,
                        false, Map.of("keyword", "Kafka")),
                intent(IntentType.ADMIN_PUBLISH_CONTENT, "admin.publish_content", RiskLevel.RISKY_WRITE,
                        true, Map.of("sourceType", "BLOG", "sourceId", 1L)))) {
            assertThat(strategies.select(intent, validator.validate(intent)).route()).isEqualTo(AgentRoute.MCP_TOOL);
        }
    }

    @Test
    void missingEntitiesAndMissingToolDefinitionCannotExecute() {
        IntentResult intent = intent(IntentType.ADMIN_PUBLISH_CONTENT, "admin.publish_content",
                RiskLevel.RISKY_WRITE, true, Map.of("sourceType", "BLOG"));
        assertThat(strategies.select(intent, validator.validate(intent)).route()).isEqualTo(AgentRoute.CLARIFY);
        assertThat(strategies.select(intent, IntentValidator.ValidationResult.builder()
                .status(IntentValidator.Status.EXECUTE).build()).route()).isEqualTo(AgentRoute.CLARIFY);
    }

    private static IntentResult intent(IntentType type, String tool, RiskLevel risk,
                                       boolean confirmation, Map<String, Object> entities) {
        return new IntentResult(type, tool, 0.95, "en", null, entities, risk,
                confirmation, List.of(), null);
    }
}
