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
import site.yuqi.agent.safety.OutputSafetyContext;

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
        when(safetyService.checkOutputWithContext(any(OutputSafetyContext.class), any())).thenReturn(pass);
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

    @Test
    void inputWarnContinuesThroughPlannerAndCarriesAllowListedConstraints() {
        SafetyCheckResult warn = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.WARN)
                .checkType("input")
                .reason("Downstream policy review is appropriate.")
                .category("AMBIGUOUS")
                .confidence(0.74)
                .constraints(List.of("PUBLIC_INFORMATION_ONLY", "STATE_UNCERTAINTY"))
                .build();
        when(safetyService.checkInput(anyString(), any())).thenReturn(warn);

        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.91, "en", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "PUBLIC_ESTIMATE", List.of("LABEL_AS_ESTIMATE"), null);
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.knowledge(intent));
        when(knowledgeClient.search(anyString(), anyInt())).thenReturn(null);
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("A qualified public-context estimate."));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s-warn")
                        .question("What can be reasonably inferred from the public profile?")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.get("stage"))
                .contains("knowledge_retrieval", "answer_final", "done");

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(generationService).streamGenerate(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Input Safety Advisory")
                .contains("PUBLIC_INFORMATION_ONLY", "STATE_UNCERTAINTY");
    }

    @Test
    void deepModeResearchesGeneralQuestionsAndPublishesReadableStepsAndSources() {
        IntentResult intent = new IntentResult(
                IntentType.GENERAL_CHAT, null, 0.92, "zh", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "STANDARD", List.of(), "正在搜索公开资料");
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.generalChat(intent, "普通模式的简短回答"));
        when(knowledgeClient.search(anyString(), anyInt())).thenReturn(null);
        when(generationService.streamGenerateGrounded(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just(
                        new GeminiGenerationService.GroundedChunk(
                                "高盛在中国设有业务实体。",
                                List.of(new GeminiGenerationService.GroundedSource(
                                        "https://www.goldmansachs.com/worldwide/china/",
                                        "Goldman Sachs in China")))));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s-deep")
                        .question("高盛公司在中国有分公司吗？")
                        .mode("DEEPTHINKING")
                        .build())
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.get("stage"))
                .contains("knowledge_retrieval", "web_research", "reasoning_step", "sources_found", "answer_final", "done");
        List<Map<String, Object>> webResearchEvents = events.stream()
                .filter(event -> "web_research".equals(event.get("stage")))
                .toList();
        assertThat(webResearchEvents).hasSize(2);
        assertThat(webResearchEvents).extracting(event -> event.get("status"))
                .containsExactly("started", "completed");
        assertThat(webResearchEvents.get(1).get("stageId"))
                .isEqualTo(webResearchEvents.get(0).get("stageId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> webResearchPayload =
                (Map<String, Object>) webResearchEvents.get(1).get("payload");
        assertThat(webResearchPayload).containsEntry("sourcesFound", 1);
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(generationService).streamGenerateGrounded(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("do not tell the user to search")
                .contains("Infer which facts and output fields are needed")
                .contains("state what could not")
                .contains("be verified instead of filling gaps")
                .doesNotContain("location questions", "cities or offices");
        verify(generationService, never()).streamGenerate(anyString(), anyString());
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

    @Test
    void publicEstimatePolicyPassesThroughToAnswer() {
        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.9, "zh", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "PUBLIC_ESTIMATE",
                List.of("PUBLIC_CONTEXT_ONLY", "LABEL_AS_ESTIMATE", "STATE_ASSUMPTIONS", "NO_PRIVATE_RECORD_CLAIM"),
                "正在基于公开信息估算...");
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.knowledge(intent));
        when(knowledgeClient.search(anyString(), anyInt())).thenReturn(null);
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("根据公开履历，估计年薪约为某个区间。"));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1").question("他的工资大概多少").build())
                .collectList().block();

        assertThat(events).isNotNull();
        // Should not be blocked
        assertThat(events).extracting(e -> e.get("stage"))
                .contains("answer_final", "done")
                .doesNotContain("error");
        // Verify context-aware safety was called (not plain checkOutput)
        verify(safetyService).checkOutputWithContext(any(OutputSafetyContext.class), any());
    }

    @Test
    void warnVerdictTriggersOneRewriteThenPasses() {
        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.9, "zh", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "PUBLIC_ESTIMATE",
                List.of("LABEL_AS_ESTIMATE"), null);
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.knowledge(intent));
        when(knowledgeClient.search(anyString(), anyInt())).thenReturn(null);
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("他的真实工资是X万。"));

        SafetyCheckResult warn = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.WARN).checkType("output_ctx")
                .reason("implies private record access").build();
        SafetyCheckResult passAfter = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.PASS).checkType("output_ctx").build();
        when(safetyService.checkOutputWithContext(any(OutputSafetyContext.class), any()))
                .thenReturn(warn).thenReturn(passAfter);
        when(generationService.generate(anyString(), anyString()))
                .thenReturn("根据公开信息估计...");

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1").question("salary?").build())
                .collectList().block();

        assertThat(events).isNotNull();
        // Should pass after rewrite, not block
        Map<String, Object> finalEvent = events.stream()
                .filter(e -> "answer_final".equals(e.get("stage")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) finalEvent.get("payload");
        assertThat(payload.get("answer").toString()).contains("公开信息");
        // generate() called once for rewrite
        verify(generationService).generate(anyString(), anyString());
    }

    @Test
    void blockVerdictAfterRewriteRefuses() {
        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.9, "en", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "STANDARD", List.of(), null);
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.knowledge(intent));
        when(knowledgeClient.search(anyString(), anyInt())).thenReturn(null);
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("Here is the exact private salary record."));

        SafetyCheckResult warn = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.WARN).checkType("output_ctx")
                .reason("claims private record").build();
        SafetyCheckResult block = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.BLOCK).checkType("output_ctx")
                .reason("still claims private record").build();
        when(safetyService.checkOutputWithContext(any(OutputSafetyContext.class), any()))
                .thenReturn(warn).thenReturn(block);
        when(generationService.generate(anyString(), anyString()))
                .thenReturn("Still contains private data.");

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1").question("exact salary?").build())
                .collectList().block();

        assertThat(events).isNotNull();
        Map<String, Object> finalEvent = events.stream()
                .filter(e -> "answer_final".equals(e.get("stage")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) finalEvent.get("payload");
        // Should contain a refusal, not the private content
        assertThat(payload.get("answer").toString()).contains("apologize");
    }
}
