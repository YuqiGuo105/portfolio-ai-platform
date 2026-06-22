package site.yuqi.agent.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;

/**
 * Non-streaming REST surface for the intent pipeline.
 *
 * <ul>
 *   <li>{@code POST /api/intent}         – classify a fresh utterance.</li>
 *   <li>{@code POST /api/intent/confirm} – submit {@code pendingActionId} +
 *       {@code confirm} to execute a previously staged write tool.</li>
 * </ul>
 *
 * <p>The streaming chat path ({@code /api/chat}) is the public widget surface;
 * this controller is intended for tests, admin tools, and internal callers
 * that prefer a JSON response over SSE.
 */
@RestController
@RequestMapping("/api/intent")
@RequiredArgsConstructor
public class IntentController {

    private final IntentOrchestrator orchestrator;

    @PostMapping
    public IntentResponse classify(@Valid @RequestBody IntentRequest request) {
        return orchestrator.handle(request);
    }

    @PostMapping("/confirm")
    public IntentResponse confirm(@RequestBody IntentRequest request) {
        if (request.getPendingActionId() == null) {
            return IntentResponse.error("pendingActionId is required.");
        }
        if (request.getConfirm() == null) {
            return IntentResponse.error("confirm (true|false) is required.");
        }
        return orchestrator.handle(request);
    }
}
