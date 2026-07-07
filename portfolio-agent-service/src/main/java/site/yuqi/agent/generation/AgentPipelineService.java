package site.yuqi.agent.generation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;
import site.yuqi.agent.client.KnowledgeClient;
import site.yuqi.agent.handoff.HandoffReason;
import site.yuqi.agent.handoff.HandoffService;
import site.yuqi.agent.intent.IntentClassificationException;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;
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
    private final HandoffService handoffService;
    private final EventRecorder eventRecorder;
    private final LlmAgentRoutePlanner routePlanner;
    private final IntentOrchestrator intentOrchestrator;

    private static final String SYSTEM_PROMPT = """
            You are Yuqi's AI assistant on his portfolio website (yuqi.site).
            You help visitors learn about Yuqi's work, projects, skills, and experience.
            
            Guidelines:
            - Answer based on the provided context (knowledge base chunks).
            - If context is insufficient, say so honestly rather than making things up.
            - Be concise, friendly, and professional.
            - Support both English and Chinese — respond in the same language as the question.
            - When referencing projects or blog posts, mention their titles.
            - For technical questions about Yuqi's work, provide specific details from context.
            """;

    private static final String TOOL_ANSWER_SYSTEM_PROMPT = """
            You are Yuqi's AI assistant. Convert a backend tool result into a concise user-facing answer.
            Use only the provided tool result. Do not invent data.
            For analytics results, describe aggregate metrics only and do not expose personal identifiers.
            Respond in the same language as the user's question.
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
                                "runMode", "production"))
                        .build());

                // Stage 1: Input safety check
                sink.next(stageEvent("safety_check", "Checking input safety..."));
                SafetyCheckResult inputSafety = safetyService.checkInput(question, runId);
                if (inputSafety.verdict() == SafetyVerdict.BLOCK) {
                    log.warn("Input blocked for session={}: {}", sessionId, inputSafety.reason());

                    // Emit: answer.blocked
                    eventRecorder.record(PlatformEvent.now(EventTypes.ANSWER_BLOCKED)
                            .runId(runId)
                            .service("agent-runtime-service")
                            .status("blocked")
                            .payload(Map.of(
                                    "blockStage", "input",
                                    "reason", inputSafety.reason() != null ? inputSafety.reason() : "safety_block",
                                    "riskFlags", List.of("prompt_injection"),
                                    "fallbackAction", "safe_refusal"))
                            .build());

                    // Emit: agent_run.completed
                    emitRunCompleted(runId, pipelineStart, "blocked");

                    sink.next(answerFinalEvent("I'm sorry, I can't help with that request."));
                    sink.next(doneEvent());
                    sink.complete();
                    return;
                }

                IntentRequest intentRequest = buildIntentRequest(request, question, sessionId);

                if (request.getPendingActionId() != null && !request.getPendingActionId().isBlank()) {
                    sink.next(stageEvent("tool_execution", "Completing confirmed action..."));
                    IntentResponse response = intentOrchestrator.handle(intentRequest);
                    handleToolResponse(sink, request, response, runId, pipelineStart);
                    return;
                }

                boolean confirmMode = "CONFIRM_HANDOFF".equals(request.getMode());
                if (confirmMode) {
                    completeHandoff(sink, request, question, runId, pipelineStart);
                    return;
                }

                // Stage 1b: LLM-owned route planning
                sink.next(stageEvent("routing", "Routing request..."));
                AgentRouteDecision routeDecision;
                try {
                    routeDecision = routePlanner.plan(intentRequest);
                } catch (IntentClassificationException e) {
                    routeDecision = routePlanner.classificationError(e);
                }

                sink.next(stageEvent("routing", "Route selected",
                        routePayload(routeDecision)));

                switch (routeDecision.route()) {
                    case MCP_TOOL -> {
                        IntentResponse response = intentOrchestrator.handlePreclassified(
                                intentRequest, routeDecision.intent());
                        handleToolResponse(sink, request, response, runId, pipelineStart);
                        return;
                    }
                    case CLARIFY -> {
                        emitRunCompleted(runId, pipelineStart, "clarify");
                        sink.next(answerFinalEvent(nonBlank(routeDecision.message(),
                                "Could you clarify what you need?")));
                        sink.next(doneEvent());
                        sink.complete();
                        return;
                    }
                    case HANDOFF -> {
                        requestHandoffConfirmation(sink, routeDecision);
                        emitRunCompleted(runId, pipelineStart, "handoff_pending");
                        sink.next(doneEvent());
                        sink.complete();
                        return;
                    }
                    case GENERAL_CHAT -> {
                        emitRunCompleted(runId, pipelineStart, "general_chat");
                        sink.next(answerFinalEvent(nonBlank(routeDecision.message(),
                                "I can help with Yuqi's portfolio, site analytics, content operations, and support workflows.")));
                        sink.next(doneEvent());
                        sink.complete();
                        return;
                    }
                    case KNOWLEDGE_QA -> {
                        // Continue into the retrieval + grounded generation path below.
                    }
                }

                // Stage 2: Knowledge retrieval
                sink.next(stageEvent("knowledge_retrieval", "Searching knowledge base..."));
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

                sink.next(stageEvent("knowledge_retrieval",
                        "Found " + chunkCount + " relevant chunks",
                        Map.of("chunksFound", chunkCount, "final", true)));

                // Stage 3: Build prompt with context + conversation history
                sink.next(stageEvent("generating", "Generating answer..."));
                String userPrompt = buildUserPrompt(question, contextChunks, request);

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
                            int generationLatency = (int) (System.currentTimeMillis() - generationStart);

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
                            SafetyCheckResult outputSafety = safetyService.checkOutput(fullAnswer, runId);
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
                                sink.next(answerFinalEvent("I apologize, but I cannot provide that response."));
                            } else {
                                // Emit: answer.generated
                                eventRecorder.record(PlatformEvent.now(EventTypes.ANSWER_GENERATED)
                                        .runId(runId)
                                        .service("agent-runtime-service")
                                        .latencyMs((int) (System.currentTimeMillis() - pipelineStart))
                                        .status("answered")
                                        .payload(Map.of(
                                                "answerLength", fullAnswer.length(),
                                                "chunksUsed", chunkCount,
                                                "inputSafetyVerdict", inputSafety.verdict().name(),
                                                "outputSafetyVerdict", outputSafety.verdict().name(),
                                                "agentVersion", "agent_v4",
                                                "model", "gemini-2.5-pro"))
                                        .build());

                                emitRunCompleted(runId, pipelineStart, "completed");
                                sink.next(answerFinalEvent(fullAnswer));
                            }
                            sink.next(doneEvent());
                            sink.complete();
                        })
                        .doOnError(e -> {
                            log.error("Generation failed for session={}", sessionId, e);

                            // Emit: agent_run.completed (failed)
                            emitRunCompleted(runId, pipelineStart, "failed");

                            sink.next(answerFinalEvent(
                                    answerBuf.isEmpty()
                                            ? "Sorry, I encountered an error generating a response."
                                            : answerBuf.toString()));
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

    private IntentRequest buildIntentRequest(AgentStreamRequest request, String question, String sessionId) {
        return IntentRequest.builder()
                .sessionId(nonBlank(sessionId, "anonymous-session"))
                .userId(request.getUserId())
                .userEmail(request.getUserEmail())
                .userRoles(request.getUserRoles())
                .utterance(nonBlank(question, "confirm"))
                .pageContext(request.getExt())
                .pendingActionId(request.getPendingActionId())
                .confirm(request.getConfirm())
                .recentMessages(recentMessages(request))
                .build();
    }

    private List<Map<String, String>> recentMessages(AgentStreamRequest request) {
        if (request.getConversationHistory() == null || request.getConversationHistory().isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, request.getConversationHistory().size() - 6);
        return request.getConversationHistory().subList(start, request.getConversationHistory().size()).stream()
                .filter(turn -> turn.getContent() != null && !turn.getContent().isBlank())
                .map(turn -> Map.of(
                        "role", nonBlank(turn.getRole(), "user"),
                        "content", turn.getContent()))
                .toList();
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
        }
        return payload;
    }

    private void handleToolResponse(FluxSink<Map<String, Object>> sink,
                                    AgentStreamRequest request,
                                    IntentResponse response,
                                    UUID runId,
                                    long pipelineStart) {
        if (response == null) {
            emitRunCompleted(runId, pipelineStart, "failed");
            sink.next(answerFinalEvent("Tool returned no response."));
            sink.next(doneEvent());
            sink.complete();
            return;
        }

        sink.next(stageEvent("tool_result", "Tool response received",
                intentResponsePayload(response)));

        String answer = renderIntentResponse(request, response);
        SafetyCheckResult outputSafety = safetyService.checkOutput(answer, runId);
        if (outputSafety.verdict() == SafetyVerdict.BLOCK) {
            log.warn("Tool answer blocked for session={}", request.getSessionId());
            emitRunCompleted(runId, pipelineStart, "blocked");
            sink.next(answerFinalEvent("I apologize, but I cannot provide that response."));
        } else {
            emitRunCompleted(runId, pipelineStart,
                    "OK".equals(response.getType()) ? "tool_completed" : response.getType().toLowerCase());
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

    private void requestHandoffConfirmation(FluxSink<Map<String, Object>> sink, AgentRouteDecision decision) {
        sink.next(stageEvent("handoff_confirm", "Confirming handoff...",
                Map.of("requiresConfirmation", true, "reason", "USER_REQUESTED")));
        sink.next(answerFinalEvent(nonBlank(decision.message(),
                "I can connect you with a human support agent. Please confirm and provide an email for follow-up."),
                Map.of("requiresConfirmation", true, "handoffReason", "USER_REQUESTED")));
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
        sink.next(answerFinalEvent(
                "I've created a support ticket for you. A human agent will follow up shortly. "
                        + "Your ticket ID is: " + ticketId.toString().substring(0, 8),
                Map.of("ticketId", ticketId.toString())));
        emitRunCompleted(runId, pipelineStart, "handoff");
        sink.next(doneEvent());
        sink.complete();
    }

    private String buildUserPrompt(String question, String context, AgentStreamRequest request) {
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

        // Add conversation history
        if (request.getConversationHistory() != null && !request.getConversationHistory().isEmpty()) {
            sb.append("## Conversation History\n");
            List<AgentStreamRequest.ConversationTurn> history = request.getConversationHistory();
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                AgentStreamRequest.ConversationTurn turn = history.get(i);
                sb.append(turn.getRole()).append(": ").append(turn.getContent()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Question\n");
        sb.append(question);

        return sb.toString();
    }

    // --- SSE event builders (match ChatWidget expected format) ---

    private Map<String, Object> stageEvent(String stage, String message) {
        return Map.of("stage", stage, "message", message);
    }

    private Map<String, Object> stageEvent(String stage, String message, Map<String, Object> payload) {
        return Map.of("stage", stage, "message", message, "payload", payload);
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

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
