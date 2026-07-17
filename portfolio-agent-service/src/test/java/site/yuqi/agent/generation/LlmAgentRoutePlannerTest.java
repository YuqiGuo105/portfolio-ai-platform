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

class LlmAgentRoutePlannerTest {

    private IntentClassifier classifier;
    private LlmAgentRoutePlanner planner;
    private IntentRequest request;

    @BeforeEach
    void setUp() {
        classifier = mock(IntentClassifier.class);
        planner = new LlmAgentRoutePlanner(classifier, mock(IntentValidator.class));
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

    private static IntentResult pendingIntent(IntentType type, double confidence, String question) {
        return new IntentResult(
                type, null, confidence, "en", null, Map.of(),
                RiskLevel.READ_ONLY, false, List.of(), question);
    }
}
