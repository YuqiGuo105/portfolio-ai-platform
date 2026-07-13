package site.yuqi.agent.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.yuqi.agent.budget.BudgetDecision;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.client.KnowledgeClient;
import site.yuqi.agent.conversation.ConversationContextLoader;
import site.yuqi.agent.conversation.MemoryWriter;
import site.yuqi.agent.conversation.PlannerContext;
import site.yuqi.agent.handoff.HandoffService;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.intent.IntentType;
import site.yuqi.agent.intent.RiskLevel;
import site.yuqi.agent.model.AgentStreamRequest;
import site.yuqi.agent.observability.EventRecorder;
import site.yuqi.agent.safety.SafetyCheckResult;
import site.yuqi.agent.safety.SafetyService;
import site.yuqi.agent.safety.SafetyVerdict;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPipelineServiceRouteTest {

    private SafetyService safetyService;
    private KnowledgeClient knowledgeClient;
    private GeminiGenerationService generationService;
    private ResponseLanguageService responseLanguageService;
    private LlmAgentRoutePlanner routePlanner;
    private IntentOrchestrator intentOrchestrator;
    private ConversationContextLoader contextLoader;
    private MemoryWriter memoryWriter;
    private ChatBudgetService chatBudgetService;
    private AgentPipelineService service;

    @BeforeEach
    void setUp() {
        safetyService = mock(SafetyService.class);
        knowledgeClient = mock(KnowledgeClient.class);
        generationService = mock(GeminiGenerationService.class);
        responseLanguageService = mock(ResponseLanguageService.class);
        routePlanner = mock(LlmAgentRoutePlanner.class);
        intentOrchestrator = mock(IntentOrchestrator.class);
        contextLoader = mock(ConversationContextLoader.class);
        memoryWriter = mock(MemoryWriter.class);
        chatBudgetService = mock(ChatBudgetService.class);

        service = new AgentPipelineService(
                safetyService,
                knowledgeClient,
                generationService,
                responseLanguageService,
                mock(HandoffService.class),
                mock(EventRecorder.class),
                routePlanner,
                intentOrchestrator,
                contextLoader,
                memoryWriter,
                chatBudgetService);

        SafetyCheckResult pass = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.PASS)
                .checkType("test")
                .build();
        when(safetyService.checkInput(anyString(), any())).thenReturn(pass);
        when(safetyService.checkOutput(anyString(), any())).thenReturn(pass);
        when(contextLoader.load(any(), any())).thenReturn(PlannerContext.empty(List.of()));
        when(responseLanguageService.alignToInputLanguage(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(chatBudgetService.reserveChatRequest()).thenReturn(BudgetDecision.allowed(
                new BigDecimal("2.00"),
                new BigDecimal("0.05"),
                new BigDecimal("1.95"),
                new BigDecimal("0.05"),
                Instant.parse("2026-07-10T00:00:00Z")));
    }

    @Test
    void mcpToolRouteDoesNotSearchKnowledgeBase() {
        IntentResult intent = analyticsIntent();
        when(routePlanner.plan(any(IntentRequest.class))).thenReturn(AgentRouteDecision.tool(intent));
        when(intentOrchestrator.handlePreclassified(any(IntentRequest.class), eq(intent)))
                .thenReturn(IntentResponse.ok(intent, Map.of(
                        "totalVisits", 171,
                        "dimensions", List.of("city", "deviceCategory"))));
        when(generationService.generate(anyString(), anyString()))
                .thenReturn("过去 7 天的访问数据已经通过 analytics MCP 工具返回。");

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1")
                        .question("具体的城市有哪些？设备？")
                        .conversationHistory(List.of(
                                new AgentStreamRequest.ConversationTurn("user", "recent visitors?"),
                                new AgentStreamRequest.ConversationTurn("assistant", "需要确认过去 7 天。")))
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.get("stage"))
                .contains("routing", "tool_call_start", "tool_call_result", "tool_result", "answer_final", "done")
                .doesNotContain("knowledge_retrieval");
        Map<String, Object> completedRouting = events.stream()
                .filter(event -> "routing".equals(event.get("stage")))
                .filter(event -> "completed".equals(event.get("status")))
                .findFirst()
                .orElseThrow();
        assertThat(completedRouting)
                .containsEntry("final", true)
                .containsKey("durationMs")
                .containsKey("stageId");
        verify(knowledgeClient, never()).search(anyString(), anyInt());
    }

    @Test
    void confirmationResponseCarriesPendingActionIdWithoutKnowledgeSearch() {
        IntentResult intent = analyticsIntent();
        when(routePlanner.plan(any(IntentRequest.class))).thenReturn(AgentRouteDecision.tool(intent));
        when(intentOrchestrator.handlePreclassified(any(IntentRequest.class), eq(intent)))
                .thenReturn(IntentResponse.confirmation(
                        "Analyze aggregate analytics from 2026-06-30 to 2026-07-06?",
                        "pending-123",
                        intent));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1")
                        .question("recent visitors?")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        Map<String, Object> finalEvent = events.stream()
                .filter(event -> "answer_final".equals(event.get("stage")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) finalEvent.get("payload");
        assertThat(payload)
                .containsEntry("pendingActionId", "pending-123")
                .containsEntry("responseType", "CONFIRMATION_REQUIRED");
        verify(knowledgeClient, never()).search(anyString(), anyInt());
    }

    @Test
    void exhaustedDailyBudgetStopsBeforeSafetyAndRouting() {
        when(chatBudgetService.reserveChatRequest()).thenReturn(BudgetDecision.denied(
                "daily_budget_exhausted",
                new BigDecimal("2.00"),
                new BigDecimal("2.00"),
                BigDecimal.ZERO,
                new BigDecimal("0.05"),
                Instant.parse("2026-07-10T00:00:00Z")));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1")
                        .question("recent visitors?")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.get("stage"))
                .containsExactly("budget_check", "answer_final", "done");
        verify(safetyService, never()).checkInput(anyString(), any());
        verify(routePlanner, never()).plan(any(IntentRequest.class));
        verify(knowledgeClient, never()).search(anyString(), anyInt());
    }

    private static IntentResult analyticsIntent() {
        return new IntentResult(
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
    }
}
