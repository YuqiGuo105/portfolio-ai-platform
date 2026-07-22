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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class LlmAgentRoutePlannerTest {

    private IntentClassifier classifier;
    private IntentValidator validator;
    private LlmAgentRoutePlanner planner;
    private IntentRequest request;

    @BeforeEach
    void setUp() {
        classifier = mock(IntentClassifier.class);
        validator = mock(IntentValidator.class);
        planner = new LlmAgentRoutePlanner(classifier, validator);
        ReflectionTestUtils.setField(planner, "pendingDecisionConfidence", 0.80);
        request = IntentRequest.builder()
                .sessionId("s1")
                .pendingActionId("pending-1")
                .utterance("raw user response")
                .pendingActionContext(Map.of("targetTool", "contact.email_owner"))
                .build();
    }

    @Test
    void acceptsOnlyHighConfidenceExplicitConfirmation() {
        when(classifier.classify(request)).thenReturn(pendingIntent(
                IntentType.PENDING_ACTION_CONFIRM, 0.97, null));

        LlmAgentRoutePlanner.PendingActionDecision decision = planner.planPendingAction(request);

        assertThat(decision.type())
                .isEqualTo(LlmAgentRoutePlanner.PendingActionDecisionType.CONFIRM);
    }

    @Test
    void keepsPendingActionWhenDecisionIsUnclear() {
        when(classifier.classify(request)).thenReturn(pendingIntent(
                IntentType.PENDING_ACTION_CONFIRM, 0.61,
                "Please clearly confirm or cancel."));

        LlmAgentRoutePlanner.PendingActionDecision decision = planner.planPendingAction(request);

        assertThat(decision.type())
                .isEqualTo(LlmAgentRoutePlanner.PendingActionDecisionType.CLARIFY);
        assertThat(decision.message()).contains("confirm or cancel");
    }

    @Test
    void routesModelSelectedWebGuideWithoutToolExecution() {
        IntentResult intent = new IntentResult(
                IntentType.WEB_GUIDE, null, 0.96, "en", null,
                Map.of("guideTargetKeys", List.of("home.projects")),
                RiskLevel.READ_ONLY, false, List.of(), null);
        when(classifier.classify(request)).thenReturn(intent);
        when(validator.validate(any(IntentResult.class))).thenReturn(
                IntentValidator.ValidationResult.builder()
                        .status(IntentValidator.Status.GENERAL_CHAT)
                        .build());

        AgentRouteDecision decision = planner.plan(request);

        assertThat(decision.route()).isEqualTo(AgentRoute.WEB_GUIDE);
        assertThat(decision.intent()).isSameAs(intent);
    }

    private static IntentResult pendingIntent(IntentType type, double confidence, String question) {
        return new IntentResult(
                type, null, confidence, "en", null, Map.of(),
                RiskLevel.READ_ONLY, false, List.of(), question);
    }
}
