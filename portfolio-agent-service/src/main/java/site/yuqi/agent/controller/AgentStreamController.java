package site.yuqi.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import site.yuqi.agent.generation.AgentPipelineService;
import site.yuqi.agent.model.AgentStreamRequest;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.io.IOException;
import java.util.Map;

/**
 * Streaming endpoint compatible with the ChatWidget frontend.
 * Accepts the same body format the widget currently sends and
 * emits SSE events it already knows how to parse.
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class AgentStreamController {

    private final AgentPipelineService pipelineService;
    private final ObjectMapper objectMapper;

    @Value("${agent.sse.timeout-ms:120000}")
    private long sseTimeoutMs;

    @PostMapping(value = "/answer/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentStreamRequest request, HttpServletRequest httpReq) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(httpReq);
        switch (principal.source()) {
            case USER_JWT -> {
                request.setUserId(principal.userId());
                request.setUserEmail(principal.email());
                request.setUserRoles(principal.rolesCsv());
            }
            case ANONYMOUS -> {
                request.setUserId(null);
                request.setUserEmail(null);
                request.setUserRoles(null);
            }
            case INTERNAL_PROXY -> {
                // Trust body — the proxy already authenticated the end user.
            }
        }

        log.info("Agent stream request session={} question={}",
                request.getSessionId(),
                request.getQuestion() != null ? request.getQuestion().substring(0, Math.min(80, request.getQuestion().length())) : "null");

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);

        Disposable subscription = pipelineService.runPipeline(request)
                .subscribe(
                        event -> safeSend(emitter, event),
                        err -> {
                            log.warn("Stream error session={}", request.getSessionId(), err);
                            safeSend(emitter, Map.of("stage", "error",
                                    "payload", Map.of("message", err.getMessage())));
                            emitter.complete();
                        },
                        emitter::complete
                );

        emitter.onCompletion(subscription::dispose);
        emitter.onTimeout(() -> {
            log.warn("SSE timeout session={}", request.getSessionId());
            subscription.dispose();
            emitter.complete();
        });
        emitter.onError(t -> subscription.dispose());

        return emitter;
    }

    private void safeSend(SseEmitter emitter, Map<String, Object> event) {
        try {
            String stage = (String) event.getOrDefault("stage", "message");
            emitter.send(SseEmitter.event()
                    .name(stage)
                    .data(objectMapper.writeValueAsString(event), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            log.debug("SSE send failed (client likely disconnected): {}", e.toString());
            emitter.completeWithError(e);
        }
    }
}
