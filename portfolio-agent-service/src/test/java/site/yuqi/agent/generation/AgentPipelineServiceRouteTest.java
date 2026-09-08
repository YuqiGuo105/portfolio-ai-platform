package site.yuqi.agent.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.yuqi.agent.attachment.AttachmentContextService;
import site.yuqi.agent.budget.BudgetDecision;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.client.KnowledgeClient;
import site.yuqi.agent.conversation.ConversationContextLoader;
import site.yuqi.agent.conversation.MemoryWriter;
import site.yuqi.agent.conversation.PlannerContext;
import site.yuqi.agent.handoff.HandoffService;
import site.yuqi.agent.guide.WebGuidePlanService;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.intent.IntentType;
import site.yuqi.agent.intent.GenerationTier;
import site.yuqi.agent.intent.RiskLevel;
import site.yuqi.agent.model.AgentStreamRequest;
import site.yuqi.agent.observability.EventRecorder;
import site.yuqi.agent.safety.SafetyCheckResult;
import site.yuqi.agent.safety.SafetyService;
import site.yuqi.agent.safety.SafetyVerdict;
import site.yuqi.agent.safety.OutputSafetyContext;
import site.yuqi.agent.workflow.AgentRunLifecycle;
import site.yuqi.ai.contracts.event.PlatformEvent;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private EventRecorder eventRecorder;
    private AttachmentContextService attachmentContextService;
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
        eventRecorder = mock(EventRecorder.class);
        attachmentContextService = mock(AttachmentContextService.class);

        service = new AgentPipelineService(
                safetyService,
                knowledgeClient,
                generationService,
                responseLanguageService,
                mock(HandoffService.class),
                eventRecorder,
                routePlanner,
                intentOrchestrator,
                contextLoader,
                memoryWriter,
                chatBudgetService,
                new WebGuidePlanService(),
                attachmentContextService,
                new AgentRunLifecycle(eventRecorder));

        SafetyCheckResult pass = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.PASS)
                .checkType("test")
                .build();
        when(safetyService.checkInput(anyString(), any())).thenReturn(pass);
        when(safetyService.checkOutput(anyString(), any())).thenReturn(pass);
        when(safetyService.checkOutputWithContext(any(OutputSafetyContext.class), any())).thenReturn(pass);
        when(contextLoader.load(any(), any())).thenReturn(PlannerContext.empty(List.of()));
        when(attachmentContextService.resolve(any(), anyString(), any()))
                .thenReturn(AttachmentContextService.AttachmentContext.empty());
        when(responseLanguageService.alignToInputLanguage(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(generationService.modelFor(anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0, Boolean.class)
                        ? "gemini-2.5-pro" : "gemini-2.5-flash");
        when(chatBudgetService.reserveChatRequest()).thenReturn(BudgetDecision.allowed(
                new BigDecimal("2.00"),
                new BigDecimal("0.05"),
                new BigDecimal("1.95"),
                new BigDecimal("0.05"),
                Instant.parse("2026-07-10T00:00:00Z")));
        when(chatBudgetService.evaluateHighCostPath())
                .thenReturn(ChatBudgetService.HighCostPathDecision.allowed("within_budget", null));
    }

    @Test
    void mcpToolRouteDoesNotSearchKnowledgeBase() {
        IntentResult intent = analyticsIntent(GenerationTier.DEEP);
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
    void webGuideRouteEmitsValidatedTourPlanWithoutKnowledgeSearch() {
        IntentResult intent = new IntentResult(
                IntentType.WEB_GUIDE, null, 0.96, "zh", null,
                Map.of(
                        "guideTargetKeys", List.of("home.projects", "home.dashboard", "invalid.target"),
                        "guideStartMode", "START_NOW",
                        "guideResponseMessage", "我来带你查看项目和实时平台面板。"),
                RiskLevel.READ_ONLY, false, List.of(), null);
        when(routePlanner.plan(any(IntentRequest.class))).thenReturn(AgentRouteDecision.webGuide(intent));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("guide-session")
                        .question("带我看看这个网站")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.get("stage"))
                .contains("routing", "tour_steps", "answer_final", "done")
                .doesNotContain("knowledge_retrieval");
        Map<String, Object> tourEvent = events.stream()
                .filter(event -> "tour_steps".equals(event.get("stage")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) tourEvent.get("payload");
        assertThat(payload)
                .containsEntry("language", "zh")
                .containsEntry("autoStart", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) payload.get("steps");
        assertThat(steps).extracting(step -> step.get("targetKey"))
                .containsExactly("home.projects", "home.dashboard");
        verify(knowledgeClient, never()).search(anyString(), anyInt());
    }

    @Test
    void pendingActionUsesLlmDecisionBeforeExecuting() {
        IntentResult decisionIntent = new IntentResult(
                IntentType.PENDING_ACTION_CONFIRM, null, 0.98,
                "zh", null, Map.of(), RiskLevel.READ_ONLY,
                false, List.of(), null);
        IntentResult contactIntent = new IntentResult(
                IntentType.CONTACT_EMAIL_OWNER, "contact.email_owner", 1.0,
                "zh", null,
                Map.of("email", "visitor@example.com", "message", "Hello world"),
                RiskLevel.SAFE_WRITE, true, List.of(), null);
        when(routePlanner.planPendingAction(any(IntentRequest.class)))
                .thenReturn(new LlmAgentRoutePlanner.PendingActionDecision(
                        LlmAgentRoutePlanner.PendingActionDecisionType.CONFIRM,
                        decisionIntent, null));
        when(intentOrchestrator.handle(any(IntentRequest.class)))
                .thenReturn(IntentResponse.ok(contactIntent,
                        Map.of("message", "Your message was sent to the site owner.")));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1")
                        .question("raw confirmation response")
                        .pendingActionId("pending-contact")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.get("stage"))
                .contains("confirmation_decision", "tool_execution", "tool_result", "answer_final", "done")
                .doesNotContain("knowledge_retrieval");
        ArgumentCaptor<IntentRequest> requestCaptor = ArgumentCaptor.forClass(IntentRequest.class);
        verify(intentOrchestrator).handle(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getConfirm()).isTrue();
        verify(knowledgeClient, never()).search(anyString(), anyInt());
    }

    @Test
    void unclearPendingDecisionPreservesPendingAction() {
        IntentResult decisionIntent = new IntentResult(
                IntentType.PENDING_ACTION_CLARIFY, null, 0.95,
                "zh", null, Map.of(), RiskLevel.READ_ONLY,
                false, List.of(), "请明确确认或取消该操作。");
        when(routePlanner.planPendingAction(any(IntentRequest.class)))
                .thenReturn(new LlmAgentRoutePlanner.PendingActionDecision(
                        LlmAgentRoutePlanner.PendingActionDecisionType.CLARIFY,
                        decisionIntent, "请明确确认或取消该操作。"));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1")
                        .question("ambiguous response")
                        .pendingActionId("pending-contact")
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
                .containsEntry("pendingActionId", "pending-contact")
                .containsEntry("responseType", "CONFIRMATION_REQUIRED");
        verify(intentOrchestrator, never()).handle(any(IntentRequest.class));
    }

    @Test
    void cancelledPendingActionUsesCancellationProgressCard() {
        IntentResult decisionIntent = new IntentResult(
                IntentType.PENDING_ACTION_CANCEL, null, 0.97,
                "zh", null, Map.of(), RiskLevel.READ_ONLY,
                false, List.of(), null);
        when(routePlanner.planPendingAction(any(IntentRequest.class)))
                .thenReturn(new LlmAgentRoutePlanner.PendingActionDecision(
                        LlmAgentRoutePlanner.PendingActionDecisionType.CANCEL,
                        decisionIntent, null));
        when(intentOrchestrator.handle(any(IntentRequest.class)))
                .thenReturn(IntentResponse.ask("Cancelled. The action was not performed."));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s1")
                        .question("cancel pending action")
                        .pendingActionId("pending-contact")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        Map<String, Object> progress = events.stream()
                .filter(event -> "pending_action".equals(event.get("stage")))
                .findFirst()
                .orElseThrow();
        assertThat(progress.get("message")).isEqualTo("Cancelling pending action...");
        assertThat(events).extracting(event -> event.get("stage"))
                .doesNotContain("tool_execution");
    }

    @Test
    void clarifyRouteRecordsTheFinalAnswerBeforeRunCompletion() {
        IntentResult intent = analyticsIntent();
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.clarify(intent, "Which time range should I use?"));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s-clarify")
                        .question("Show me the visitors")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        ArgumentCaptor<PlatformEvent> eventCaptor = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventRecorder, org.mockito.Mockito.atLeastOnce()).record(eventCaptor.capture());
        assertThat(eventCaptor.getAllValues().stream()
                .filter(event -> "agent_run.state_changed".equals(event.eventType()))
                .map(event -> event.payload().get("state")))
                .containsExactly("RECEIVED", "ADMITTED", "GUARDING", "PLANNING", "FINALIZING", "COMPLETED");
        List<PlatformEvent> recorded = eventCaptor.getAllValues().stream()
                .filter(event -> !"agent_run.state_changed".equals(event.eventType())).toList();
        assertThat(recorded).extracting(PlatformEvent::eventType)
                .containsExactly("agent_run.started", "agent_step.routing_completed", "answer.generated", "agent_run.completed");
        assertThat(recorded.get(2).payload())
                .containsEntry("answer", "Which time range should I use?")
                .containsEntry("route", "CLARIFY");
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
                .containsExactly("run_metadata", "budget_check", "answer_final", "done");
        var recorded = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventRecorder, org.mockito.Mockito.atLeastOnce()).record(recorded.capture());
        Object finalPayload = events.stream().filter(event -> "answer_final".equals(event.get("stage")))
                .findFirst().orElseThrow().get("payload");
        assertThat(recorded.getAllValues()).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("answer.generated");
            assertThat(event.payload()).containsEntry("answer", ((Map<?, ?>) finalPayload).get("answer"))
                    .containsEntry("question", "recent visitors?");
            assertThat(event.status()).isEqualTo("budget_exhausted");
        });
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
                .constraints(List.of("PUBLIC_INFORMATION_ONLY", "NO_PROTECTED_DATA_ACCESS"))
                .build();
        when(safetyService.checkInput(anyString(), any())).thenReturn(warn);

        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.91, "en", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "PUBLIC_ESTIMATE", List.of("LABEL_AS_ESTIMATE"), null);
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.knowledge(intent));
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
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

        var systemPromptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(generationService).streamGenerate(systemPromptCaptor.capture(), promptCaptor.capture());
        assertThat(systemPromptCaptor.getValue())
                .contains("closed-world source of truth")
                .contains("Never complete gaps from model", "memory, common assumptions")
                .contains("Public first-party profiles");
        assertThat(promptCaptor.getValue())
                .contains("Input Safety Advisory")
                .contains("PUBLIC_INFORMATION_ONLY", "NO_PROTECTED_DATA_ACCESS")
                .contains("does not prohibit using facts intentionally published")
                .contains("Evidence Fidelity")
                .contains("never substitute model memory");
    }

    @Test
    void personalHistoryFollowUpKeepsRelationshipAndCountryThroughRetrievalAndGeneration() {
        String normalized = "Yuqi travel history cities visited in the United States";
        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.97, "zh", normalized,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null);
        when(routePlanner.plan(any(IntentRequest.class))).thenReturn(AgentRouteDecision.knowledge(intent));
        when(contextLoader.load(any(), any())).thenReturn(PlannerContext.empty(List.of(
                Map.of("role", "user", "content", "郭育奇去过哪些地方？"))));
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("公开证据暂不足以列出城市。"));

        var events = service.runPipeline(AgentStreamRequest.builder()
                .sessionId("travel-session").conversationId("travel-conversation")
                .question("美国哪些城市？").build()).collectList().block();

        assertThat(events).isNotNull();
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(knowledgeClient).search(query.capture(), eq(6));
        assertThat(query.getValue()).contains("美国哪些城市？", normalized);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(generationService).streamGenerate(system.capture(), user.capture());
        assertThat(user.getValue()).contains("郭育奇去过哪些地方？", normalized,
                "Resolved Search Intent (not evidence)", "latest explicit question takes precedence");
        assertThat(system.getValue()).contains("not visitor analytics", "distinguish cities from states",
                "does not mean an event never happened", "requires login",
                "Previous assistant replies", "OWNER_QA", "does not establish a number");
        verify(intentOrchestrator, never()).handlePreclassified(any(), any());
    }

    @Test
    void interviewEvidenceRetainsAttributionAndSourceWithoutTreatingMentionsAsInterviews() {
        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.97, "en", "Yuqi companies interviewed at",
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null);
        when(routePlanner.plan(any(IntentRequest.class))).thenReturn(AgentRouteDecision.knowledge(intent));
        String evidence = "I interviewed at Example Corp. Example Labs was only a comparison.";
        String url = "https://www.yuqi.site/life-blog/interview-test";
        var hit = KnowledgeSearchResponse.ChunkHit.builder().chunkId("interview-test-0")
                .documentId("interview-test").title("Interview journal").content(evidence)
                .score(0.9).sourceType("LIFE_BLOG").sourceId("interview-test").sourceUrl(url).build();
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of(hit)).build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("According to his journal, Example Corp."));

        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("interview-session")
                .question("Which companies did Yuqi interview with?").build()).collectList().block();

        assertThat(events).isNotNull();
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(generationService).streamGenerate(system.capture(), user.capture());
        assertThat(user.getValue()).contains(evidence, "Source URL: " + url);
        assertThat(system.getValue()).contains("Interviewed at, applied to,", "distinct claims",
                "not evidence of participation", "not to the current visitor");
        assertThat(events).extracting(event -> event.get("stage")).contains("related_links", "done");
    }

    @Test
    void knowledgeRoutePublishesCanonicalRelatedLinkAndPassesItToGeneration() {
        IntentResult intent = new IntentResult(
                IntentType.KNOWLEDGE_QA, null, 0.97, "en", "Portfolio Platform",
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "GROUNDED", List.of(), GenerationTier.STANDARD, "Looking up the project");
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.knowledge(intent));
        KnowledgeSearchResponse.ChunkHit hit = KnowledgeSearchResponse.ChunkHit.builder()
                .chunkId("project-1-0")
                .documentId("project-1")
                .title("Portfolio Platform")
                .content("A production-minded event-driven portfolio platform.")
                .score(0.9)
                .sourceType("PROJECT")
                .sourceId("project-1")
                .sourceUrl("https://www.yuqi.site/work-single/project-1")
                .build();
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder()
                        .queryId("query-1")
                        .results(List.of(hit))
                        .latencyMs(15)
                        .build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just(
                        "Read [Portfolio Platform](https://www.yuqi.site/work-single/project-1)."));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("project-session")
                        .question("Portfolio Platform")
                        .build())
                .collectList()
                .block();

        assertThat(events).isNotNull();
        Map<String, Object> related = events.stream()
                .filter(event -> "related_links".equals(event.get("stage")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links =
                (List<Map<String, Object>>) ((Map<String, Object>) related.get("payload")).get("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0))
                .containsEntry("title", "Portfolio Platform")
                .containsEntry("url", "https://www.yuqi.site/work-single/project-1");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationService).streamGenerate(anyString(), promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains("Source URL: https://www.yuqi.site/work-single/project-1");
        var recorded = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventRecorder, org.mockito.Mockito.atLeastOnce()).record(recorded.capture());
        var retrieval = recorded.getAllValues().stream()
                .filter(event -> "retrieval.completed".equals(event.eventType())).findFirst().orElseThrow();
        assertThat(retrieval.payload()).containsEntry("queryId", "query-1");
        assertThat(retrieval.payload().get("sources").toString()).contains("project-1-0", "Portfolio Platform");
    }

    @Test
    void outputVerificationRunsOffEventLoopAndUnavailableCheckDoesNotWasteRewrite() {
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.generalChat(null, ""));
        when(generationService.streamGenerate(anyString(), anyString())).thenReturn(
                reactor.core.publisher.Flux.just("A general explanation.")
                        .publishOn(reactor.core.scheduler.Schedulers.parallel()));
        when(safetyService.checkOutputWithContext(any(), any())).thenAnswer(invocation -> {
            assertThat(reactor.core.scheduler.Schedulers.isInNonBlockingThread()).isFalse();
            return SafetyCheckResult.builder().verdict(SafetyVerdict.WARN).category("UNKNOWN")
                    .reason("Safety classifier unavailable").build();
        });
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("scheduler-test")
                .question("Explain a queue").build()).collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events).extracting(event -> event.get("stage")).contains("answer_final", "done");
        verify(safetyService, times(1)).checkOutputWithContext(any(), any());
        verify(generationService, never()).generate(anyString(), anyString());
        assertThat(events.toString()).doesNotContain("A general explanation.", "answer_delta");
        assertThat(events.toString()).contains("could not verify");
    }

    @Test
    void plannerSelectedDeepTierResearchesGeneralQuestionsAndPublishesReadableStepsAndSources() {
        IntentResult intent = new IntentResult(
                IntentType.GENERAL_CHAT, null, 0.92, "zh", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "STANDARD", List.of(), GenerationTier.DEEP, "正在搜索公开资料");
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.generalChat(intent, "普通模式的简短回答"));
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
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

    @Test
    void standardGeneralQuestionUsesGeneratorRatherThanPlannerCannedMessage() {
        IntentResult intent = new IntentResult(IntentType.GENERAL_CHAT, null, 0.95, "en", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null);
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.generalChat(intent, "I only help with the portfolio."));
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("A transaction groups operations atomically."));
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("general-test")
                .question("Explain a database transaction").build()).collectList().block();
        assertThat(events).extracting(event -> event.get("stage")).contains("generating", "answer_final", "done");
        verify(generationService).streamGenerate(anyString(), anyString());
        assertThat(events.toString()).doesNotContain("I only help with the portfolio.");
        verify(knowledgeClient, never()).search(anyString(), anyInt());
    }

    @Test
    void costGuardrailDowngradesDeepResearchToStandardGeneration() {
        IntentResult intent = new IntentResult(
                IntentType.GENERAL_CHAT, null, 0.92, "zh", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "STANDARD", List.of(), GenerationTier.DEEP, "正在搜索公开资料");
        when(routePlanner.plan(any(IntentRequest.class)))
                .thenReturn(AgentRouteDecision.generalChat(intent, "普通模式的简短回答"));
        when(chatBudgetService.evaluateHighCostPath())
                .thenReturn(ChatBudgetService.HighCostPathDecision.denied("daily_budget_near_limit", null));
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("标准模型回答。"));

        List<Map<String, Object>> events = service.runPipeline(AgentStreamRequest.builder()
                        .sessionId("s-cost")
                        .question("帮我搜索一个需要公开资料的问题")
                        .build())
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(event -> event.get("stage"))
                .contains("cost_guardrail", "knowledge_retrieval", "generating", "answer_final", "done")
                .doesNotContain("web_research", "sources_found");
        verify(generationService).streamGenerate(anyString(), anyString());
        verify(generationService, never()).streamGenerateGrounded(anyString(), anyString());
        verify(chatBudgetService).recordHighCostDowngrade("daily_budget_near_limit");
        verify(chatBudgetService).recordModelCall("gemini-2.5-flash", false, false);
    }

    private static IntentResult analyticsIntent() {
        return analyticsIntent(GenerationTier.STANDARD);
    }

    private static IntentResult analyticsIntent(GenerationTier generationTier) {
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
                null,
                "STANDARD",
                List.of(),
                generationTier,
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
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
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
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("他的真实工资是X万。"));

        SafetyCheckResult warn = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.WARN).checkType("output_ctx")
                .category("POLICY_VIOLATION").reason("implies private record access").build();
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
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of()).build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("Here is the exact private salary record."));

        SafetyCheckResult warn = SafetyCheckResult.builder()
                .verdict(SafetyVerdict.WARN).checkType("output_ctx")
                .category("POLICY_VIOLATION").reason("claims private record").build();
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
        assertThat(events.toString()).doesNotContain("exact private salary record", "Still contains private data", "answer_delta");
    }

    @Test
    void retrievalOutageIsNotZeroHitsAndDoesNotGenerateOwnerFacts() {
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.knowledge(null));
        when(knowledgeClient.search(anyString(), anyInt())).thenReturn(null);
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("retrieval-outage")
                .question("Where did the owner study?").build()).collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events.toString()).contains("knowledge service is temporarily unavailable");
        assertThat(events).extracting(event -> event.get("stage")).contains("done").doesNotContain("answer_delta");
        verify(generationService, never()).streamGenerate(anyString(), anyString());
        var recorded = ArgumentCaptor.forClass(PlatformEvent.class);
        verify(eventRecorder, org.mockito.Mockito.atLeastOnce()).record(recorded.capture());
        assertThat(recorded.getAllValues()).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("retrieval.completed");
            assertThat(event.status()).isEqualTo("unavailable");
            assertThat(event.payload()).containsEntry("zeroHit", false);
        });
    }

    @Test
    void interruptedGenerationDoesNotPublishPartialAnswerOrProviderDetails() {
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.generalChat(null, ""));
        when(generationService.streamGenerate(anyString(), anyString())).thenReturn(
                reactor.core.publisher.Flux.concat(reactor.core.publisher.Flux.just("Unverified partial draft"),
                        reactor.core.publisher.Flux.error(new IllegalStateException("private-provider-detail"))));
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("generation-outage")
                .question("Explain a queue").build()).collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events.toString()).doesNotContain("Unverified partial draft", "private-provider-detail", "answer_delta");
        assertThat(events.toString()).contains("could not be completed");
        assertThat(events).extracting(event -> event.get("stage")).contains("answer_final", "done");
        verify(safetyService, never()).checkOutputWithContext(any(), any());
    }

    @Test
    void groundingCorrectionUsesOriginalEvidenceAndPublishesOnlyCheckedAnswer() {
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.knowledge(null));
        var hit = KnowledgeSearchResponse.ChunkHit.builder().chunkId("synthetic-education")
                .content("The owner graduated from Example University.").title("Reviewed education").build();
        when(knowledgeClient.search(anyString(), anyInt()))
                .thenReturn(KnowledgeSearchResponse.builder().results(List.of(hit)).build());
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("The owner graduated from Wrong University."));
        when(safetyService.checkOutputWithContext(any(), any())).thenReturn(
                SafetyCheckResult.builder().verdict(SafetyVerdict.WARN).category("UNGROUNDED")
                        .reason("University contradicts source").build(),
                SafetyCheckResult.builder().verdict(SafetyVerdict.PASS).build());
        when(generationService.generate(anyString(), anyString())).thenReturn("The owner graduated from Example University.");
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("grounding-correction")
                .question("Where did the owner study?").build()).collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events.toString()).doesNotContain("Wrong University", "answer_delta").contains("Example University");
        var checks = ArgumentCaptor.forClass(OutputSafetyContext.class);
        verify(safetyService, times(2)).checkOutputWithContext(checks.capture(), any());
        assertThat(checks.getAllValues()).allSatisfy(context ->
                assertThat(context.groundingEvidence()).contains(hit.content()).doesNotContain("Wrong University"));
        var rewrite = ArgumentCaptor.forClass(String.class);
        verify(generationService).generate(anyString(), rewrite.capture());
        assertThat(rewrite.getValue()).contains(hit.content(), "Where did the owner study?", "Correct or remove unsupported claims");
    }

    @Test
    void resolvedAttachmentIsIncludedInGenerationAndVerification() {
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.generalChat(null, ""));
        when(attachmentContextService.resolve(any(), anyString(), any()))
                .thenReturn(new AttachmentContextService.AttachmentContext("Synthetic document: the total is 42.", List.of(), true));
        when(generationService.streamGenerate(anyString(), anyString())).thenReturn(reactor.core.publisher.Flux.just("The total is 42."));
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("attachment-evidence")
                .question("What is the total in my document?").build()).collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events.toString()).contains("The total is 42.");
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(generationService).streamGenerate(anyString(), prompt.capture());
        assertThat(prompt.getValue()).contains("Synthetic document: the total is 42.");
        var check = ArgumentCaptor.forClass(OutputSafetyContext.class);
        verify(safetyService).checkOutputWithContext(check.capture(), any());
        assertThat(check.getValue().groundingEvidence()).contains("Synthetic document: the total is 42.");
    }

    @Test
    void cancellingResponseCancelsUpstreamGeneration() throws Exception {
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.generalChat(null, ""));
        var subscribed = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        when(generationService.streamGenerate(anyString(), anyString())).thenReturn(
                reactor.core.publisher.Flux.<String>never().doOnSubscribe(ignored -> subscribed.countDown())
                        .doOnCancel(cancelled::countDown));
        var subscription = service.runPipeline(AgentStreamRequest.builder().sessionId("cancel-generation")
                .question("Explain a queue").build()).subscribe();
        try {
            assertThat(subscribed.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            subscription.dispose();
        }
        assertThat(cancelled.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stillUngroundedAfterOneRewriteDoesNotPublish() {
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.generalChat(null, ""));
        when(generationService.streamGenerate(anyString(), anyString()))
                .thenReturn(reactor.core.publisher.Flux.just("Invented owner fact"));
        when(safetyService.checkOutputWithContext(any(), any())).thenReturn(
                SafetyCheckResult.builder().verdict(SafetyVerdict.WARN).category("UNGROUNDED")
                        .reason("Not in the evidence").build());
        when(generationService.generate(anyString(), anyString())).thenReturn("Another invented owner fact");
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("rewrite-failed")
                .question("Explain the owner's work").build()).collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events.toString()).doesNotContain("Invented owner fact", "Another invented owner fact", "answer_delta");
        assertThat(events.toString()).contains("could not verify");
        verify(generationService, times(1)).generate(anyString(), anyString());
        verify(safetyService, times(2)).checkOutputWithContext(any(), any());
    }

    @Test
    void sourceOnlyResearchResponseDoesNotCountAsAnAnswer() {
        IntentResult intent = new IntentResult(IntentType.GENERAL_CHAT, null, 0.95, "en", null,
                Map.of(), RiskLevel.READ_ONLY, false, List.of(), null,
                "STANDARD", List.of(), GenerationTier.DEEP, "Searching");
        when(routePlanner.plan(any())).thenReturn(AgentRouteDecision.generalChat(intent, ""));
        when(generationService.streamGenerateGrounded(anyString(), anyString())).thenReturn(
                reactor.core.publisher.Flux.just(new GeminiGenerationService.GroundedChunk("", List.of(
                        new GeminiGenerationService.GroundedSource("https://example.com", "Example")))));
        var events = service.runPipeline(AgentStreamRequest.builder().sessionId("empty-research")
                .question("Research queues").build()).collectList().block(java.time.Duration.ofSeconds(10));
        assertThat(events.toString()).contains("could not be completed");
        assertThat(events).extracting(event -> event.get("stage")).contains("answer_final", "done");
        verify(safetyService, never()).checkOutputWithContext(any(), any());
    }
}
