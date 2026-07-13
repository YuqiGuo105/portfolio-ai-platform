package site.yuqi.agent.generation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import site.yuqi.agent.budget.BudgetDecision;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.client.KnowledgeClient;
import site.yuqi.agent.conversation.ConversationContextLoader;
import site.yuqi.agent.conversation.MemoryWriter;
import site.yuqi.agent.conversation.PlannerContext;
import site.yuqi.agent.handoff.HandoffReason;
import site.yuqi.agent.handoff.HandoffService;
import site.yuqi.agent.intent.IntentClassificationException;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;
import site.yuqi.agent.intent.IntentResult;
import site.yuqi.agent.model.AgentStreamRequest;
import site.yuqi.agent.observability.EventRecorder;
import site.yuqi.agent.safety.SafetyCheckResult;
import site.yuqi.agent.safety.SafetyService;
import site.yuqi.agent.safety.SafetyVerdict;
import site.yuqi.ai.contracts.event.EventTypes;
import site.yuqi.ai.contracts.event.PlatformEvent;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Full agent pipeline: safety → knowledge retrieval → LLM generation → output safety.
 * Emits SSE-compatible events matching the ChatWidget protocol.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentPipelineService {

    private final SafetyService safetyService;
    private final KnowledgeClient knowledgeClient;
    private final GeminiGenerationService generationService;
    private final ResponseLanguageService responseLanguageService;
    private final HandoffService handoffService;
    private final EventRecorder eventRecorder;
    private final LlmAgentRoutePlanner routePlanner;
    private final IntentOrchestrator intentOrchestrator;
    private final ConversationContextLoader contextLoader;
    private final MemoryWriter memoryWriter;
    private final ChatBudgetService chatBudgetService;

    private static final String SYSTEM_PROMPT = """
            You are Yuqi's AI assistant on his portfolio website (yuqi.site).
            You help visitors learn about Yuqi's work, projects, skills, and experience.
            
            Guidelines:
            - Answer based on the provided context (knowledge base chunks).
            - If context is insufficient, say so honestly rather than making things up.
            - Be concise, friendly, and professional.
            - Detect the current user's input language and write the answer in that same language.
            - Do not switch languages just because context, retrieved chunks, or recent turns use another language.
            - When referencing projects or blog posts, mention their titles.
            - For technical questions about Yuqi's work, provide specific details from context.
            - Follow the planner-provided response policy and constraints. For public-context estimates, clearly
              label the result as an estimate, state material assumptions, and never claim access to private records.
            """;

    private static final String TOOL_ANSWER_SYSTEM_PROMPT = """
            You are Yuqi's AI assistant. Convert a backend tool result into a concise user-facing answer.
            Use only the provided tool result. Do not invent data.
            For analytics results, describe aggregate metrics only and do not expose personal identifiers.
            Detect the current user's input language and write the answer in that same language.
            """;

    /**
     * Run the full pipeline, returning a Flux of SSE event maps.
     * Each map has: { stage, payload, message (optional) }
     */
    public Flux<Map<String, Object>> runPipeline(AgentStreamRequest request) {
        return Flux.create(sink -> {
            String question = request.getQuestion();
            String sessionId = request.getSessionId();
            if (question == null || question.isBlank()) {
                sink.next(errorEvent("No question provided."));
                sink.next(doneEvent());
                sink.complete();
                return;
            }

            UUID runId = UUID.randomUUID();
            long pipelineStart = System.currentTimeMillis();

            try {
                BudgetDecision budgetDecision = chatBudgetService.reserveChatRequest();
                if (!budgetDecision.allowed()) {
                    log.warn("Daily chat budget denied session={} reason={} used={} limit={}",
                            sessionId,
                            budgetDecision.reason(),
                            budgetDecision.usedUsd(),
                            budgetDecision.limitUsd());
                    sink.next(stageEvent("budget_check", "Daily chat budget exhausted",
                            budgetPayload(budgetDecision)));
                    emitRunCompleted(runId, pipelineStart, "budget_exhausted");
                    sink.next(answerFinalEvent(budgetExceededMessage(budgetDecision),
                            budgetPayload(budgetDecision)));
                    sink.next(doneEvent());
                    sink.complete();
                    return;
                }

                PlannerContext plannerContext = contextLoader.load(
                        request.getConversationId(),
                        List.of());

                // Emit: agent_run.started
                eventRecorder.record(PlatformEvent.now(EventTypes.AGENT_RUN_STARTED)
                        .runId(runId)
                        .service("agent-runtime-service")
                        .status("running")
                        .payload(Map.of(
                                "question", question,
                                "sessionId", sessionId != null ? sessionId : "",
                                "agentVersion", "agent_v4",
                                "promptProfile", "portfolio_assistant",
                                "runMode", "production",
                                "dailyBudget", budgetPayload(budgetDecision)))
                        .build());

                IntentRequest intentRequest = buildIntentRequest(request, question, sessionId, plannerContext);

                if (request.getPendingActionId() != null && !request.getPendingActionId().isBlank()) {
                    SafetyCheckResult inputSafety = checkInputSafety(sink, question, runId);
                    if (inputSafety.verdict() == SafetyVerdict.BLOCK) {
                        handleBlockedInput(sink, request, question, sessionId, runId, pipelineStart, inputSafety);
                        return;
                    }
                    sink.next(stageEvent("tool_execution", "Completing confirmed action..."));
                    IntentResponse response = intentOrchestrator.handle(intentRequest);
                    handleToolResponse(sink, request, response, runId, pipelineStart, question);
                    return;
                }

                boolean confirmMode = "CONFIRM_HANDOFF".equals(request.getMode());
                if (confirmMode) {
                    SafetyCheckResult inputSafety = checkInputSafety(sink, question, runId);
                    if (inputSafety.verdict() == SafetyVerdict.BLOCK) {
                        handleBlockedInput(sink, request, question, sessionId, runId, pipelineStart, inputSafety);
                        return;
                    }
                    completeHandoff(sink, request, question, runId, pipelineStart);
                    return;
                }

                // Input safety and intent routing are independent. Running them
                // concurrently removes one full model round trip from the critical path.
                String safetyStageId = stageId(runId, "safety_check");
                String routingStageId = stageId(runId, "routing");
                sink.next(stageStarted("safety_check", "Checking request safety...", safetyStageId));
                sink.next(stageStarted("routing", "Understanding your request...", routingStageId));

                var preflight = Mono.zip(
                                Mono.fromCallable(() -> timed(() -> safetyService.checkInput(question, runId)))
                                        .subscribeOn(Schedulers.boundedElastic()),
                                Mono.fromCallable(() -> timed(() -> planRoute(intentRequest)))
                                        .subscribeOn(Schedulers.boundedElastic()))
                        .block();
                if (preflight == null) {
                    throw new IllegalStateException("Preflight checks did not complete");
                }

                TimedResult<SafetyCheckResult> safetyResult = preflight.getT1();
                TimedResult<AgentRouteDecision> routingResult = preflight.getT2();
                SafetyCheckResult inputSafety = safetyResult.value();
                AgentRouteDecision routeDecision = routingResult.value();

                sink.next(stageCompleted("safety_check", "Request safety checked", safetyStageId,
                        safetyResult.durationMs(), Map.of("verdict", inputSafety.verdict().name())));
                sink.next(stageCompleted("routing",
                        progressMessage(routeDecision.intent(), "Request understood"), routingStageId,
                        routingResult.durationMs(), routePayload(routeDecision)));

                if (inputSafety.verdict() == SafetyVerdict.BLOCK) {
                    handleBlockedInput(sink, request, question, sessionId, runId, pipelineStart, inputSafety);
                    return;
                }

                switch (routeDecision.route()) {
                    case MCP_TOOL -> {
                        String toolName = routeDecision.intent() != null
                                ? routeDecision.intent().targetTool()
                                : "unknown";
                        String callId = UUID.randomUUID().toString();
                        long toolStart = System.currentTimeMillis();
                        sink.next(toolCallStarted(callId, toolName));
                        IntentResponse response = intentOrchestrator.handlePreclassified(
                                intentRequest, routeDecision.intent());
                        sink.next(toolCallCompleted(callId, toolName,
                                (int) (System.currentTimeMillis() - toolStart), response));
                        handleToolResponse(sink, request, response, runId, pipelineStart, question);
                        return;
                    }
                    case CLARIFY -> {
                        emitRunCompleted(runId, pipelineStart, "clarify");
                        String candidate = nonBlank(routeDecision.message(), "Could you clarify what you need?");
                        String answer = plannerAlreadyLocalized(routeDecision)
                                ? candidate
                                : alignAnswer(question, candidate);
                        memoryWriter.writeTurnPair(request.getConversationId(), question, answer, "CLARIFY", (Map<String, Object>) null);
                        sink.next(answerFinalEvent(answer));
                        sink.next(doneEvent());
                        sink.complete();
                        return;
                    }
                    case HANDOFF -> {
                        String answer = requestHandoffConfirmation(sink, question, routeDecision);
                        memoryWriter.writeTurnPair(request.getConversationId(), question, answer, "HANDOFF", (Map<String, Object>) null);
                        emitRunCompleted(runId, pipelineStart, "handoff_pending");
                        sink.next(doneEvent());
                        sink.complete();
                        return;
                    }
                    case GENERAL_CHAT -> {
                        emitRunCompleted(runId, pipelineStart, "general_chat");
                        String answer = alignAnswer(question, nonBlank(routeDecision.message(),
                                "I can help with Yuqi's portfolio, site analytics, content operations, and support workflows."));
                        memoryWriter.writeTurnPair(request.getConversationId(), question, answer, "GENERAL_CHAT", (Map<String, Object>) null);
                        sink.next(answerFinalEvent(answer));
                        sink.next(doneEvent());
                        sink.complete();
                        return;
                    }
                    case KNOWLEDGE_QA -> {
                        // Continue into the retrieval + grounded generation path below.
                    }
                }

                // Stage 2: Knowledge retrieval
                String retrievalStageId = stageId(runId, "knowledge_retrieval");
                sink.next(stageStarted("knowledge_retrieval",
                        progressMessage(routeDecision.intent(), "Searching public portfolio context..."),
                        retrievalStageId));
                long retrievalStart = System.currentTimeMillis();
                KnowledgeSearchResponse searchResponse = knowledgeClient.search(question, 6);
                int retrievalLatency = (int) (System.currentTimeMillis() - retrievalStart);

                String contextChunks;
                int chunkCount;
                List<String> chunkIds;
                if (searchResponse == null || searchResponse.results() == null || searchResponse.results().isEmpty()) {
                    contextChunks = "";
                    chunkCount = 0;
                    chunkIds = List.of();
                } else {
                    contextChunks = searchResponse.results().stream()
                            .map(hit -> "## " + (hit.title() != null ? hit.title() : "Chunk") + "\n" + hit.content())
                            .collect(Collectors.joining("\n---\n"));
                    chunkCount = searchResponse.results().size();
                    chunkIds = searchResponse.results().stream()
                            .map(hit -> hit.chunkId() != null ? hit.chunkId() : "unknown")
                            .toList();
                }

                // Emit: retrieval.completed
                eventRecorder.record(PlatformEvent.now(EventTypes.RETRIEVAL_COMPLETED)
                        .runId(runId)
                        .service("agent-runtime-service")
                        .latencyMs(retrievalLatency)
                        .status(chunkCount > 0 ? "success" : "zero_hit")
                        .payload(Map.of(
                                "retrievalStrategy", "hybrid_bm25_knn",
                                "topK", 6,
                                "returnedChunks", chunkCount,
                                "zeroHit", chunkCount == 0))
                        .build());

                sink.next(stageCompleted("knowledge_retrieval",
                        "Found " + chunkCount + " relevant chunks",
                        retrievalStageId, retrievalLatency, Map.of("chunksFound", chunkCount)));

                // Stage 3: Build prompt with context + conversation history
                String generationStageId = stageId(runId, "generating");
                sink.next(stageStarted("generating", "Preparing a grounded answer...", generationStageId));
                String userPrompt = buildUserPrompt(
                        question, contextChunks, request, plannerContext, routeDecision.intent());

                // Stage 4: Stream answer tokens
                long generationStart = System.currentTimeMillis();
                StringBuilder answerBuf = new StringBuilder();
                generationService.streamGenerate(SYSTEM_PROMPT, userPrompt)
                        .doOnNext(delta -> {
                            answerBuf.append(delta);
                            sink.next(answerDeltaEvent(delta));
                        })
                        .doOnComplete(() -> {
                            String fullAnswer = answerBuf.toString();
                            String finalAnswer = fullAnswer.trim();
                            int generationLatency = (int) (System.currentTimeMillis() - generationStart);
                            sink.next(stageCompleted("generating", "Answer draft completed", generationStageId,
                                    generationLatency, Map.of("outputLength", fullAnswer.length())));

                            // Emit: model_call.completed
                            eventRecorder.record(PlatformEvent.now(EventTypes.MODEL_CALL_COMPLETED)
                                    .runId(runId)
                                    .service("agent-runtime-service")
                                    .latencyMs(generationLatency)
                                    .status("success")
                                    .payload(Map.of(
                                            "provider", "google",
                                            "model", "gemini-2.5-pro",
                                            "operation", "stream_generate",
                                            "promptVersion", "portfolio_assistant_v2",
                                            "outputLength", fullAnswer.length()))
                                    .build());

                            // Stage 5: Output safety check
                            String outputSafetyStageId = stageId(runId, "output_safety");
                            sink.next(stageStarted("output_safety", "Checking the answer...",
                                    outputSafetyStageId));
                            long outputSafetyStart = System.currentTimeMillis();
                            SafetyCheckResult outputSafety = safetyService.checkOutput(finalAnswer, runId);
                            sink.next(stageCompleted("output_safety", "Answer checked", outputSafetyStageId,
                                    (int) (System.currentTimeMillis() - outputSafetyStart),
                                    Map.of("verdict", outputSafety.verdict().name())));
                            if (outputSafety.verdict() == SafetyVerdict.BLOCK) {
                                log.warn("Output blocked for session={}", sessionId);

                                // Emit: answer.blocked
                                eventRecorder.record(PlatformEvent.now(EventTypes.ANSWER_BLOCKED)
                                        .runId(runId)
                                        .service("agent-runtime-service")
                                        .status("blocked")
                                        .payload(Map.of(
                                                "blockStage", "output",
                                                "reason", outputSafety.reason() != null ? outputSafety.reason() : "output_safety",
                                                "fallbackAction", "safe_refusal"))
                                        .build());

                                emitRunCompleted(runId, pipelineStart, "blocked");
                                String answer = alignAnswer(question, "I apologize, but I cannot provide that response.");
                                memoryWriter.writeTurnPair(request.getConversationId(), question, answer,
                                        "BLOCKED", (Map<String, Object>) null);
                                sink.next(answerFinalEvent(answer));
                            } else {
                                // Emit: answer.generated
                                eventRecorder.record(PlatformEvent.now(EventTypes.ANSWER_GENERATED)
                                        .runId(runId)
                                        .service("agent-runtime-service")
                                        .latencyMs((int) (System.currentTimeMillis() - pipelineStart))
                                        .status("answered")
                                        .payload(Map.of(
                                                "answerLength", finalAnswer.length(),
                                                "chunksUsed", chunkCount,
                                                "inputSafetyVerdict", inputSafety.verdict().name(),
                                                "outputSafetyVerdict", outputSafety.verdict().name(),
                                                "agentVersion", "agent_v4",
                                                "model", "gemini-2.5-pro"))
                                        .build());

                                emitRunCompleted(runId, pipelineStart, "completed");
                                memoryWriter.writeTurnPair(request.getConversationId(), question, finalAnswer,
                                        "KNOWLEDGE_QA", (Map<String, Object>) null);
                                sink.next(answerFinalEvent(finalAnswer));
                            }
                            sink.next(doneEvent());
                            sink.complete();
                        })
                        .doOnError(e -> {
                            log.error("Generation failed for session={}", sessionId, e);
                            sink.next(stageCompleted("generating", "Answer generation failed", generationStageId,
                                    (int) (System.currentTimeMillis() - generationStart),
                                    Map.of("status", "failed")));

                            // Emit: agent_run.completed (failed)
                            emitRunCompleted(runId, pipelineStart, "failed");

                            String answer = answerBuf.isEmpty()
                                    ? "Sorry, I encountered an error generating a response."
                                    : answerBuf.toString();
                            answer = alignAnswer(question, answer);
                            memoryWriter.writeTurnPair(request.getConversationId(), question, answer,
                                    "ERROR", (Map<String, Object>) null);
                            sink.next(answerFinalEvent(answer));
                            sink.next(doneEvent());
                            sink.complete();
                        })
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe();

            } catch (Exception e) {
                log.error("Pipeline error for session={}", sessionId, e);
                emitRunCompleted(runId, pipelineStart, "failed");
                sink.next(errorEvent("Internal error: " + e.getMessage()));
                sink.next(doneEvent());
                sink.complete();
            }
        });
    }

    private SafetyCheckResult checkInputSafety(FluxSink<Map<String, Object>> sink,
                                               String question,
                                               UUID runId) {
        String stageId = stageId(runId, "safety_check");
        sink.next(stageStarted("safety_check", "Checking request safety...", stageId));
        long start = System.currentTimeMillis();
        SafetyCheckResult result = safetyService.checkInput(question, runId);
        sink.next(stageCompleted("safety_check", "Request safety checked", stageId,
                (int) (System.currentTimeMillis() - start),
                Map.of("verdict", result.verdict().name())));
        return result;
    }

    private AgentRouteDecision planRoute(IntentRequest intentRequest) {
        try {
            return routePlanner.plan(intentRequest);
        } catch (IntentClassificationException e) {
            return routePlanner.classificationError(e);
        }
    }

    private void handleBlockedInput(FluxSink<Map<String, Object>> sink,
                                    AgentStreamRequest request,
                                    String question,
                                    String sessionId,
                                    UUID runId,
                                    long pipelineStart,
                                    SafetyCheckResult inputSafety) {
        log.warn("Input blocked for session={}: {}", sessionId, inputSafety.reason());
        eventRecorder.record(PlatformEvent.now(EventTypes.ANSWER_BLOCKED)
                .runId(runId)
                .service("agent-runtime-service")
                .status("blocked")
                .payload(Map.of(
                        "blockStage", "input",
                        "reason", inputSafety.reason() != null ? inputSafety.reason() : "safety_block",
                        "fallbackAction", "safe_refusal"))
                .build());
        emitRunCompleted(runId, pipelineStart, "blocked");

        String answer = alignAnswer(question, "I'm sorry, I can't help with that request.");
        memoryWriter.writeTurnPair(request.getConversationId(), question, answer,
                "BLOCKED", (Map<String, Object>) null);
        sink.next(answerFinalEvent(answer));
        sink.next(doneEvent());
        sink.complete();
    }

    private static <T> TimedResult<T> timed(Supplier<T> operation) {
        long start = System.currentTimeMillis();
        T value = operation.get();
        return new TimedResult<>(value, (int) (System.currentTimeMillis() - start));
    }

    private record TimedResult<T>(T value, int durationMs) {
    }

    private void emitRunCompleted(UUID runId, long pipelineStart, String finalStatus) {
        try {
            eventRecorder.record(PlatformEvent.now(EventTypes.AGENT_RUN_COMPLETED)
                    .runId(runId)
                    .service("agent-runtime-service")
                    .latencyMs((int) (System.currentTimeMillis() - pipelineStart))
                    .status(finalStatus)
                    .payload(Map.of("finalStatus", finalStatus))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to emit agent_run.completed: {}", e.getMessage());
        }
    }

    private IntentRequest buildIntentRequest(AgentStreamRequest request,
                                             String question,
                                             String sessionId,
                                             PlannerContext plannerContext) {
        return IntentRequest.builder()
                .sessionId(nonBlank(sessionId, "anonymous-session"))
                .conversationId(request.getConversationId())
                .userId(request.getUserId())
                .userEmail(request.getUserEmail())
                .userRoles(request.getUserRoles())
                .utterance(nonBlank(question, "confirm"))
                .pageContext(request.getExt())
                .pendingActionId(request.getPendingActionId())
                .confirm(request.getConfirm())
                .recentMessages(plannerContext.recentMessages())
                .compactSummary(plannerContext.compactSummary())
                .structuredState(plannerContext.structuredState())
                .pendingActionContext(plannerContext.pendingAction())
                .build();
    }

    private Map<String, Object> routePayload(AgentRouteDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("route", decision.route().name());
        if (decision.intent() != null) {
            payload.put("intent", decision.intent().intent().name());
            if (decision.intent().targetTool() != null) {
                payload.put("targetTool", decision.intent().targetTool());
            }
            payload.put("confidence", decision.intent().confidence());
            payload.put("requiresConfirmation", decision.intent().requiresConfirmation());
            payload.put("responsePolicy", decision.intent().responsePolicy());
            if (!decision.intent().responseConstraints().isEmpty()) {
                payload.put("responseConstraints", decision.intent().responseConstraints());
            }
        }
        return payload;
    }

    private void handleToolResponse(FluxSink<Map<String, Object>> sink,
                                    AgentStreamRequest request,
                                    IntentResponse response,
                                    UUID runId,
                                    long pipelineStart,
                                    String question) {
        if (response == null) {
            emitRunCompleted(runId, pipelineStart, "failed");
            sink.next(answerFinalEvent(alignAnswer(question, "Tool returned no response.")));
            sink.next(doneEvent());
            sink.complete();
            return;
        }

        sink.next(stageEvent("tool_result", "Tool response received",
                intentResponsePayload(response)));

        String rendered = renderIntentResponse(request, response);
        boolean trustedStructuredResponse = isTrustedStructuredToolResponse(response);
        String answer = trustedStructuredResponse
            ? rendered
            : alignAnswer(question, rendered);
        SafetyVerdict outputVerdict = trustedStructuredResponse
                ? SafetyVerdict.PASS
                : safetyService.checkOutput(answer, runId).verdict();
        if (outputVerdict == SafetyVerdict.BLOCK) {
            log.warn("Tool answer blocked for session={}", request.getSessionId());
            emitRunCompleted(runId, pipelineStart, "blocked");
            String blocked = alignAnswer(question, "I apologize, but I cannot provide that response.");
            memoryWriter.writeTurnPair(request.getConversationId(), question, blocked, "BLOCKED", response);
            sink.next(answerFinalEvent(blocked));
        } else {
            emitRunCompleted(runId, pipelineStart,
                    "OK".equals(response.getType()) ? "tool_completed" : response.getType().toLowerCase());
            memoryWriter.writeTurnPair(request.getConversationId(), question, answer, response.getType(), response);
            sink.next(answerFinalEvent(answer, answerPayload(response)));
        }

        sink.next(doneEvent());
        sink.complete();
    }

    private Map<String, Object> intentResponsePayload(IntentResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", response.getType());
        if (response.getPendingActionId() != null) {
            payload.put("pendingActionId", response.getPendingActionId());
        }
        if (response.getIntent() != null && response.getIntent().targetTool() != null) {
            payload.put("targetTool", response.getIntent().targetTool());
        }
        return payload;
    }

    private Map<String, Object> answerPayload(IntentResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("responseType", response.getType());
        if (response.getPendingActionId() != null) {
            payload.put("pendingActionId", response.getPendingActionId());
        }
        if (response.getOptions() != null) {
            payload.put("options", response.getOptions());
        }
        if (response.getIntent() != null && response.getIntent().targetTool() != null) {
            payload.put("targetTool", response.getIntent().targetTool());
        }
        return payload;
    }

    private Map<String, Object> budgetPayload(BudgetDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limitUsd", decision.limitUsd());
        payload.put("usedUsd", decision.usedUsd());
        payload.put("remainingUsd", decision.remainingUsd());
        payload.put("reservedUsd", decision.reservedUsd());
        payload.put("resetAt", decision.resetAt().toString());
        if (decision.reason() != null) {
            payload.put("reason", decision.reason());
        }
        return payload;
    }

    private String budgetExceededMessage(BudgetDecision decision) {
        return "The daily chat agent budget has been reached. "
                + "Limit: $" + decision.limitUsd().toPlainString()
                + ", used today: $" + decision.usedUsd().toPlainString()
                + ". Please try again after " + decision.resetAt() + ".";
    }

    private String renderIntentResponse(AgentStreamRequest request, IntentResponse response) {
        return switch (response.getType()) {
            case "OK" -> renderToolAnswer(request, response);
            case "CONFIRMATION_REQUIRED", "ASK", "FORBIDDEN", "ERROR", "GENERAL_CHAT" ->
                    nonBlank(response.getMessage(), "I need a bit more information before continuing.");
            default -> nonBlank(response.getMessage(), String.valueOf(response.getResult()));
        };
    }

    private String renderToolAnswer(AgentStreamRequest request, IntentResponse response) {
        Object result = response.getResult();
        String language = response.getIntent() != null ? response.getIntent().language() : "en";
        if (result == null) {
            return isChinese(language) ? "已完成。" : "Done.";
        }
        if (isSubscriptionOtpWorkflow(response)) {
            if (result instanceof Map<?, ?> map && map.get("message") != null) {
                return String.valueOf(map.get("message"));
            }
            if ("subscription.confirm_unsubscribe".equals(response.getIntent().targetTool())) {
                return isChinese(language)
                        ? "已退订成功。"
                        : "The subscription status is now UNSUBSCRIBED.";
            }
            return isChinese(language)
                    ? "如果该邮箱存在有效订阅，验证码已发送。"
                    : "If this address has an active subscription, a verification code has been sent.";
        }
        if (isDirectRenderable(result)) {
            String compact = compactToolResult(result);
            return isChinese(language)
                    ? "已完成。结果：\n" + compact
                    : "Done. Result:\n" + compact;
        }
        String prompt = """
                User question:
                %s

                Tool:
                %s

                Tool result:
                %s
                """.formatted(
                nonBlank(request.getQuestion(), ""),
                response.getIntent() != null ? response.getIntent().targetTool() : "unknown",
                result);
        try {
            String generated = generationService.generate(TOOL_ANSWER_SYSTEM_PROMPT, prompt);
            if (generated != null && !generated.isBlank()) {
                return generated;
            }
        } catch (Exception e) {
            log.warn("Tool answer rendering failed for session={}: {}", request.getSessionId(), e.toString());
        }
        return "Tool result: " + result;
    }

    private boolean isTrustedStructuredToolResponse(IntentResponse response) {
        return response != null
                && "OK".equals(response.getType())
                && response.getIntent() != null
                && isDirectRenderable(response.getResult());
    }

    private boolean isDirectRenderable(Object result) {
        return result == null
                || result instanceof Map<?, ?>
                || result instanceof Iterable<?>
                || result instanceof String
                || result instanceof Number
                || result instanceof Boolean;
    }

    private String compactToolResult(Object result) {
        if (result == null) return "";
        if (result instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .limit(10)
                    .map(e -> String.valueOf(e.getKey()) + ": " + compactValue(e.getValue()))
                    .collect(Collectors.joining("\n"));
        }
        if (result instanceof Iterable<?> iterable) {
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= 8) break;
                sb.append("- ").append(compactValue(item)).append("\n");
            }
            return sb.toString().trim();
        }
        return compactValue(result);
    }

    private String compactValue(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    private boolean isChinese(String language) {
        return language != null && language.toLowerCase().startsWith("zh");
    }

    private boolean isSubscriptionOtpWorkflow(IntentResponse response) {
        return response != null
                && response.getIntent() != null
                && ("subscription.request_unsubscribe_code".equals(response.getIntent().targetTool())
                    || "subscription.confirm_unsubscribe".equals(response.getIntent().targetTool()));
    }

    private boolean plannerAlreadyLocalized(AgentRouteDecision decision) {
        return decision != null
                && decision.intent() != null
                && decision.intent().clarificationQuestion() != null
                && !decision.intent().clarificationQuestion().isBlank();
    }

    private String requestHandoffConfirmation(FluxSink<Map<String, Object>> sink,
                                              String question,
                                              AgentRouteDecision decision) {
        sink.next(stageEvent("handoff_confirm", "Confirming handoff...",
                Map.of("requiresConfirmation", true, "reason", "USER_REQUESTED")));
        String answer = alignAnswer(question, nonBlank(decision.message(),
                "I can connect you with a human support agent. Please confirm and provide an email for follow-up."));
        sink.next(answerFinalEvent(answer,
                Map.of("requiresConfirmation", true, "handoffReason", "USER_REQUESTED")));
        return answer;
    }

    private void completeHandoff(FluxSink<Map<String, Object>> sink,
                                 AgentStreamRequest request,
                                 String question,
                                 UUID runId,
                                 long pipelineStart) {
        log.info("Handoff confirmed for session={}", request.getSessionId());
        UUID conversationId = UUID.randomUUID();
        UUID ticketId = handoffService.createHandoff(
                conversationId, runId, request.getUserEmail(),
                HandoffReason.USER_REQUESTED, question);
        sink.next(stageEvent("handoff", "Connecting you to human support...",
                Map.of("ticketId", ticketId.toString(), "reason", "USER_REQUESTED")));
        String answer = alignAnswer(question, "I've created a support ticket for you. A human agent will follow up shortly. "
                + "Your ticket ID is: " + ticketId.toString().substring(0, 8));
        memoryWriter.writeTurnPair(request.getConversationId(), question, answer,
                "HANDOFF", Map.of("ticketId", ticketId.toString(), "targetTool", "human_handoff"));
        sink.next(answerFinalEvent(answer, Map.of("ticketId", ticketId.toString())));
        emitRunCompleted(runId, pipelineStart, "handoff");
        sink.next(doneEvent());
        sink.complete();
    }

    private String buildUserPrompt(String question,
                                   String context,
                                   AgentStreamRequest request,
                                   PlannerContext plannerContext,
                                   IntentResult intent) {
        StringBuilder sb = new StringBuilder();

        // Add page context if available
        if (request.getExt() != null) {
            Object pageText = request.getExt().get("pageContextText");
            Object pageTitle = request.getExt().get("pageTitle");
            if (pageText != null && !pageText.toString().isBlank()) {
                sb.append("## Current Page Context\n");
                if (pageTitle != null) sb.append("Page: ").append(pageTitle).append("\n");
                sb.append(pageText.toString(), 0, Math.min(pageText.toString().length(), 1500));
                sb.append("\n\n");
            }
        }

        // Add knowledge base context
        if (!context.isEmpty()) {
            sb.append("## Knowledge Base Context\n");
            sb.append(context);
            sb.append("\n\n");
        }

        if (plannerContext.compactSummary() != null && !plannerContext.compactSummary().isEmpty()) {
            sb.append("## Compact Conversation Summary\n");
            sb.append(plannerContext.compactSummary()).append("\n\n");
        }

        if (plannerContext.structuredState() != null && !plannerContext.structuredState().isEmpty()) {
            sb.append("## Structured Conversation State\n");
            sb.append(plannerContext.structuredState()).append("\n\n");
        }

        if (plannerContext.pendingAction() != null && !plannerContext.pendingAction().isEmpty()) {
            sb.append("## Pending Action\n");
            sb.append(plannerContext.pendingAction()).append("\n\n");
        }

        if (plannerContext.recentMessages() != null && !plannerContext.recentMessages().isEmpty()) {
            sb.append("## Recent Conversation Turns\n");
            for (Map<String, String> turn : plannerContext.recentMessages()) {
                sb.append(turn.getOrDefault("role", "user"))
                        .append(": ")
                        .append(turn.getOrDefault("content", ""))
                        .append("\n");
            }
            sb.append("\n");
        }

        if (intent != null) {
            sb.append("## Planner Response Policy\n");
            sb.append("Policy: ").append(intent.responsePolicy()).append("\n");
            if (!intent.responseConstraints().isEmpty()) {
                sb.append("Constraints: ").append(intent.responseConstraints()).append("\n");
            }
            sb.append("Apply these constraints to the answer without mentioning internal policy names.\n\n");
        }

        sb.append("## Output Language Rule\n");
        sb.append("Detect the language of the current Question and write the final answer in that same language. ");
        sb.append("Use retrieved context only for facts; do not copy its language if it differs from the Question.\n\n");

        sb.append("## Question\n");
        sb.append(question);

        return sb.toString();
    }

    private static String progressMessage(IntentResult intent, String fallback) {
        return intent == null || intent.progressMessage() == null || intent.progressMessage().isBlank()
                ? fallback
                : intent.progressMessage();
    }

    // --- SSE event builders (match ChatWidget expected format) ---

    private Map<String, Object> stageEvent(String stage, String message) {
        return Map.of("stage", stage, "message", message);
    }

    private Map<String, Object> stageEvent(String stage, String message, Map<String, Object> payload) {
        return Map.of("stage", stage, "message", message, "payload", payload);
    }

    private Map<String, Object> stageStarted(String stage, String message, String stageId) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", stage);
        event.put("stageId", stageId);
        event.put("groupKey", stageId);
        event.put("status", "started");
        event.put("startedAt", System.currentTimeMillis());
        event.put("message", message);
        return event;
    }

    private Map<String, Object> stageCompleted(String stage,
                                               String message,
                                               String stageId,
                                               int durationMs,
                                               Map<String, Object> payload) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", stage);
        event.put("stageId", stageId);
        event.put("groupKey", stageId);
        event.put("status", "completed");
        event.put("final", true);
        event.put("completedAt", System.currentTimeMillis());
        event.put("durationMs", durationMs);
        event.put("message", message);
        event.put("payload", payload == null ? Map.of() : payload);
        return event;
    }

    private Map<String, Object> toolCallStarted(String callId, String toolName) {
        return Map.of(
                "stage", "tool_call_start",
                "status", "started",
                "message", "Calling " + nonBlank(toolName, "tool"),
                "payload", Map.of(
                        "callId", callId,
                        "toolName", nonBlank(toolName, "unknown")));
    }

    private Map<String, Object> toolCallCompleted(String callId,
                                                  String toolName,
                                                  int latencyMs,
                                                  IntentResponse response) {
        return Map.of(
                "stage", "tool_call_result",
                "status", "completed",
                "final", true,
                "message", "Tool call completed",
                "payload", Map.of(
                        "callId", callId,
                        "toolName", nonBlank(toolName, "unknown"),
                        "latencyMs", latencyMs,
                        "status", response == null ? "error" : response.getType()));
    }

    private static String stageId(UUID runId, String stage) {
        return runId + ":" + stage;
    }

    private Map<String, Object> answerDeltaEvent(String delta) {
        return Map.of("stage", "answer_delta", "payload", Map.of("delta", delta));
    }

    private Map<String, Object> answerFinalEvent(String answer) {
        return Map.of("stage", "answer_final", "payload", Map.of("answer", answer));
    }

    private Map<String, Object> answerFinalEvent(String answer, Map<String, Object> extraPayload) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("answer", answer);
        if (extraPayload != null) {
            payload.putAll(extraPayload);
        }
        return Map.of("stage", "answer_final", "payload", payload);
    }

    private Map<String, Object> errorEvent(String message) {
        return Map.of("stage", "error", "payload", Map.of("message", message));
    }

    private Map<String, Object> doneEvent() {
        return Map.of("stage", "done");
    }

    private String alignAnswer(String question, String candidateAnswer) {
        return responseLanguageService.alignToInputLanguage(question, candidateAnswer);
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
