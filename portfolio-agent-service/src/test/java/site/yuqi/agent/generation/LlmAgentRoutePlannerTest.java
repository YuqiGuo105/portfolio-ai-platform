package site.yuqi.agent.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.agent.intent.IntentClassifier;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.intent.IntentType;
import site.yuqi.agent.intent.IntentValidator;
import site.yuqi.agent.intent.RiskLevel;
import site.yuqi.agent.intent.ToolRegistry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmAgentRoutePlannerTest {

    private ToolRegistry registry;
    private IntentValidator validator;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        ReflectionTestUtils.invokeMethod(registry, "init");
        validator = new IntentValidator(registry);
        ReflectionTestUtils.setField(validator, "readThreshold", 0.85);
        ReflectionTestUtils.setField(validator, "clarifyThreshold", 0.65);
    }

    @Test
    void routesLlmSelectedAnalyticsToolToMcpToolPath() {
        IntentResult intent = new IntentResult(
                IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
                "analytics.get_visitor_summary",
                0.96,
                "zh",
                "visitor analytics by city and device",
                Map.of("dimensions", List.of("city", "deviceCategory"), "timeRangePreset", "recent"),
                RiskLevel.READ_ONLY,
                true,
                List.of(),
                null);

        LlmAgentRoutePlanner planner = new LlmAgentRoutePlanner(staticClassifier(intent), validator);

        AgentRouteDecision decision = planner.plan(IntentRequest.builder()
                .sessionId("s1")
                .utterance("具体的城市有哪些？设备？")
                .build());

        assertThat(decision.route()).isEqualTo(AgentRoute.MCP_TOOL);
        assertThat(decision.intent()).isSameAs(intent);
    }

    @Test
    void routesLlmSelectedKnowledgeQaToRetrievalPath() {
        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA,
                null,
                0.91,
                "en",
                "Yuqi project knowledge question",
                Map.of(),
                RiskLevel.READ_ONLY,
                false,
                List.of(),
                null);

        LlmAgentRoutePlanner planner = new LlmAgentRoutePlanner(staticClassifier(intent), validator);

        AgentRouteDecision decision = planner.plan(IntentRequest.builder()
                .sessionId("s1")
                .utterance("What did Yuqi build with Kafka?")
                .build());

        assertThat(decision.route()).isEqualTo(AgentRoute.KNOWLEDGE_QA);
    }

    private static IntentClassifier staticClassifier(IntentResult result) {
        return request -> result;
    }
}
