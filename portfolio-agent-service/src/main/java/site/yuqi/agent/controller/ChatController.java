package site.yuqi.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import site.yuqi.agent.model.ChatRequest;
import site.yuqi.agent.model.ChatStreamEvent;
import site.yuqi.agent.service.GraphWorkflowRunner;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.io.IOException;

/**
 * Public chat surface mounted on {@code POST /api/chat}.
 *
 * <p>Uses Spring MVC {@link SseEmitter} so we stay on a servlet stack while
 * still streaming. Each event from {@link GraphWorkflowRunner} is JSON-encoded
 * and written as a Server-Sent Event with the matching {@code event:} name.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final GraphWorkflowRunner workflowRunner;
    private final ObjectMapper objectMapper;

    @Value("${agent.sse.timeout-ms:120000}")
    private long sseTimeoutMs;

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request, HttpServletRequest httpReq) {
        // Overwrite caller identity with the server-derived principal so a
        // client can't spoof admin powers by putting them in the body.
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(httpReq);
        switch (principal.source()) {
            case USER_JWT -> {
                request.setUserEmail(principal.email());
                request.setUserRoles(principal.rolesCsv());
            }
            case ANONYMOUS -> {
                request.setUserEmail(null);
                request.setUserRoles(null);
            }
            case INTERNAL_PROXY -> {
                // Trust body — the proxy already authenticated the end user.
            }
        }

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        Disposable subscription = workflowRunner.run(request)
                .subscribe(
                        event -> safeSend(emitter, event),
                        err -> {
                            log.warn("Chat stream error session={}", request.getSessionId(), err);
                            safeSend(emitter, ChatStreamEvent.builder()
                                    .type("error")
                                    .payload(err.getMessage())
                                    .build());
                            emitter.complete();
                        },
                        emitter::complete);

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            log.warn("SSE timeout session={}", request.getSessionId());
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(t -> subscription.dispose());

        return emitter;
    }

    private void safeSend(SseEmitter emitter, ChatStreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType())
                    .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("SSE send failed (client likely disconnected): {}", e.toString());
            emitter.completeWithError(e);
        }
    }
}
