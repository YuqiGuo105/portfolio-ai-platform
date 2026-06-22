package site.yuqi.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;
import site.yuqi.agent.model.ChatRequest;
import site.yuqi.agent.model.ChatStreamEvent;

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

    public Flux<ChatStreamEvent> run(ChatRequest request) {
        return Flux.create(sink -> {
            sink.next(ChatStreamEvent.builder().type("intent").payload("classifying").build());

            String utterance = lastUserUtterance(request);
            if (utterance == null) {
                sink.next(ChatStreamEvent.builder().type("error").payload("No user message provided.").build());
                sink.complete();
                return;
            }

            IntentRequest ir = IntentRequest.builder()
                    .sessionId(request.getSessionId())
                    .userEmail(request.getUserEmail())
                    .utterance(utterance)
                    .pageContext(request.getPageContext())
                    .build();

            try {
                IntentResponse resp = orchestrator.handle(ir);
                sink.next(ChatStreamEvent.builder().type(mapToEvent(resp.getType())).payload(resp).build());
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
