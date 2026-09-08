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
        planner = new LlmAgentRoutePlanner(classifier, validator, new AgentRouteStrategies());
        ReflectionTestUtils.setField(planner, "pendingDecisionConfidence", 0.80);
        ReflectionTestUtils.setField(planner, "reviewGeneralChat", true);
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
        when(classifier.reviewRoute(request, intent)).thenReturn(intent);
        when(validator.validate(any(IntentResult.class))).thenReturn(
                IntentValidator.ValidationResult.builder()
                        .status(IntentValidator.Status.GENERAL_CHAT)
                        .build());

        AgentRouteDecision decision = planner.plan(request);

        assertThat(decision.route()).isEqualTo(AgentRoute.WEB_GUIDE);
        assertThat(decision.intent()).isSameAs(intent);
    }

    @Test
    void letsTheModelCorrectAnOverBroadGeneralChatRoute() {
        IntentResult firstPass = new IntentResult(
                IntentType.GENERAL_CHAT, null, 0.95, "en", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null);
        IntentResult reviewed = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.96, "en",
                "portfolio platform architecture",
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null);
        when(classifier.classify(request)).thenReturn(firstPass);
        when(classifier.reviewRoute(request, firstPass)).thenReturn(reviewed);
        when(validator.validate(firstPass)).thenReturn(
                IntentValidator.ValidationResult.builder()
                        .status(IntentValidator.Status.GENERAL_CHAT)
                        .build());
        when(validator.validate(reviewed)).thenReturn(
                IntentValidator.ValidationResult.builder()
                        .status(IntentValidator.Status.GENERAL_CHAT)
                        .build());

        AgentRouteDecision decision = planner.plan(request);

        assertThat(decision.route()).isEqualTo(AgentRoute.KNOWLEDGE_QA);
        assertThat(decision.intent()).isSameAs(reviewed);
    }

    @Test
    void explanationOfVisibleSectionsCanBeCorrectedFromGuideToKnowledge() {
        var question = IntentRequest.builder().sessionId("s1").utterance(
                "请介绍 platform monitor 和 visitor insights 的技术、原理、workflow 和 diagrams").build();
        var first = pendingIntent(IntentType.WEB_GUIDE, 0.95, null);
        var reviewed = pendingIntent(IntentType.KNOWLEDGE_QA, 0.97, null);
        when(classifier.classify(question)).thenReturn(first);
        when(classifier.reviewRoute(question, first)).thenReturn(reviewed);
        when(validator.validate(any(IntentResult.class))).thenReturn(IntentValidator.ValidationResult.builder()
                .status(IntentValidator.Status.GENERAL_CHAT).build());
        assertThat(planner.plan(question).route()).isEqualTo(AgentRoute.KNOWLEDGE_QA);
    }

    @Test
    void preservesTheFirstPassWhenSemanticReviewIsUnavailable() {
        IntentResult firstPass = new IntentResult(
                IntentType.GENERAL_CHAT, null, 0.95, "en", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null);
        when(classifier.classify(request)).thenReturn(firstPass);
        when(classifier.reviewRoute(request, firstPass))
                .thenThrow(new site.yuqi.agent.intent.IntentClassificationException(
                        "review model unavailable"));
        when(validator.validate(firstPass)).thenReturn(
                IntentValidator.ValidationResult.builder()
                        .status(IntentValidator.Status.GENERAL_CHAT)
                        .build());

        AgentRouteDecision decision = planner.plan(request);

        assertThat(decision.route()).isEqualTo(AgentRoute.GENERAL_CHAT);
        assertThat(decision.intent()).isSameAs(firstPass);
    }

    private static IntentResult pendingIntent(IntentType type, double confidence, String question) {
        return new IntentResult(
                type, null, confidence, "en", null, Map.of(),
                RiskLevel.READ_ONLY, false, List.of(), question);
    }

    @Test
    void invalidPendingConfidenceCannotAuthorizeAWritingAction() {
        for (double confidence : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 1.1}) {
            when(classifier.classify(request)).thenReturn(pendingIntent(
                    IntentType.PENDING_ACTION_CONFIRM, confidence, null));
            assertThat(planner.planPendingAction(request).type())
                    .isEqualTo(LlmAgentRoutePlanner.PendingActionDecisionType.CLARIFY);
        }
    }

    @Test
    void rejectedResponseRouteCannotBypassValidator() {
        IntentResult intent = pendingIntent(IntentType.KNOWLEDGE_QA, 0.99, null);
        when(classifier.classify(request)).thenReturn(intent);
        when(validator.validate(intent)).thenReturn(IntentValidator.ValidationResult.builder()
                .status(IntentValidator.Status.REJECT).build());
        assertThat(planner.plan(request).route()).isEqualTo(AgentRoute.CLARIFY);
    }

    @Test
    void malformedSemanticReviewFailsClosed() {
        IntentResult initial = pendingIntent(IntentType.GENERAL_CHAT, 0.95, null);
        when(classifier.classify(request)).thenReturn(initial);
        when(validator.validate(initial)).thenReturn(IntentValidator.ValidationResult.builder()
                .status(IntentValidator.Status.GENERAL_CHAT).build());
        when(classifier.reviewRoute(request, initial)).thenReturn(null);
        when(validator.validate(null)).thenReturn(IntentValidator.ValidationResult.builder()
                .status(IntentValidator.Status.REJECT).build());
        assertThat(planner.plan(request).route()).isEqualTo(AgentRoute.CLARIFY);
    }
}
