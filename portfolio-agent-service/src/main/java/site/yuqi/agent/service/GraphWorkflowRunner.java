package site.yuqi.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import site.yuqi.agent.budget.BudgetDecision;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.conversation.ConversationContextLoader;
import site.yuqi.agent.conversation.MemoryWriter;
import site.yuqi.agent.conversation.PlannerContext;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;
import site.yuqi.agent.model.ChatRequest;
import site.yuqi.agent.model.ChatStreamEvent;

import java.util.List;
import java.util.Map;

/**
 * Bridges the streaming chat surface ({@link site.yuqi.agent.controller.ChatController})
 * to the LLM-first intent pipeline ({@link IntentOrchestrator}).
 *
 * <p>Sprint 1 emits one {@code intent} event, runs the orchestrator
 * synchronously (it already does its own internal LLM + gateway round trips),
 * and emits one terminal event matching the orchestrator's response type.
 * A future iteration can stream model tokens inside the same SSE channel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphWorkflowRunner {

    private final IntentOrchestrator orchestrator;
    private final ConversationContextLoader contextLoader;
    private final MemoryWriter memoryWriter;
    private final ChatBudgetService chatBudgetService;

    public Flux<ChatStreamEvent> run(ChatRequest request) {
        return Flux.create(sink -> {
            sink.next(ChatStreamEvent.builder().type("intent").payload("classifying").build());

            String utterance = lastUserUtterance(request);
            if (utterance == null) {
                sink.next(ChatStreamEvent.builder().type("error").payload("No user message provided.").build());
                sink.complete();
                return;
            }

            BudgetDecision budget = chatBudgetService.reserveChatRequest();
            if (!budget.allowed()) {
                log.warn("Chat budget denied session={} reason={}",
                        request.getSessionId(), budget.reason());
                sink.next(ChatStreamEvent.builder()
                        .type("error")
                        .payload(Map.of(
                                "code", "CHAT_BUDGET_UNAVAILABLE",
                                "message", "Chat is temporarily unavailable due to its configured AI usage limit.",
                                "resetAt", budget.resetAt().toString()))
                        .build());
                sink.next(ChatStreamEvent.builder().type("done").build());
                sink.complete();
                return;
            }

            PlannerContext plannerContext = contextLoader.load(
                    request.getConversationId(),
                    List.of());

            IntentRequest ir = IntentRequest.builder()
                    .sessionId(request.getSessionId())
                    .conversationId(request.getConversationId())
                    .userEmail(request.getUserEmail())
                    .userRoles(request.getUserRoles())
                    .utterance(utterance)
                    .pageContext(request.getPageContext())
                    .recentMessages(plannerContext.recentMessages())
                    .compactSummary(plannerContext.compactSummary())
                    .structuredState(plannerContext.structuredState())
                    .pendingActionContext(plannerContext.pendingAction())
                    .build();

            try {
                IntentResponse resp = orchestrator.handle(ir);
                sink.next(ChatStreamEvent.builder().type(mapToEvent(resp.getType())).payload(resp).build());
                memoryWriter.writeTurnPair(
                        request.getConversationId(),
                        utterance,
                        memoryWriter.responseText(resp),
                        resp.getType(),
                        resp);
                sink.next(ChatStreamEvent.builder().type("done").build());
                sink.complete();
            } catch (Exception e) {
                log.warn("Orchestrator threw for session={}", request.getSessionId(), e);
                sink.next(ChatStreamEvent.builder().type("error").payload(e.getMessage()).build());
                sink.complete();
            }
        });
    }

    private String lastUserUtterance(ChatRequest request) {
        if (request.getMessages() == null) return null;
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatRequest.Message m = request.getMessages().get(i);
            if ("user".equalsIgnoreCase(m.getRole()) && m.getContent() != null) {
                return m.getContent();
            }
        }
        return null;
    }

    /** Map orchestrator response type → SSE event name the widget recognizes. */
    private String mapToEvent(String type) {
        return switch (type) {
            case "OK"                    -> "answer";
            case "ASK"                   -> "clarify";
            case "CONFIRMATION_REQUIRED" -> "confirm";
            case "FORBIDDEN"             -> "forbidden";
            case "GENERAL_CHAT"          -> "answer";
            case "ERROR"                 -> "error";
            default                      -> "answer";
        };
    }
}
