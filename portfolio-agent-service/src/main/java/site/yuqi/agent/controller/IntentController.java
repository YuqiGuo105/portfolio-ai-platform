package site.yuqi.agent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.IntentResponse;
import site.yuqi.agent.web.AuthenticatedPrincipal;

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
 *
 * <p>{@code SupabaseJwtAuthFilter} authenticates every request and stashes an
 * {@link AuthenticatedPrincipal} on the servlet request. Both endpoints rewrite
 * {@code IntentRequest}'s identity fields from that principal so a client
 * cannot escalate privileges by putting {@code userRoles=ADMIN} in the body.
 */
@RestController
@RequestMapping("/api/intent")
@RequiredArgsConstructor
public class IntentController {

    private final IntentOrchestrator orchestrator;

    @PostMapping
    public IntentResponse classify(@Valid @RequestBody IntentRequest request,
                                   HttpServletRequest httpReq) {
        return orchestrator.handle(applyPrincipal(request, httpReq));
    }

    @PostMapping("/confirm")
    public IntentResponse confirm(@RequestBody IntentRequest request,
                                  HttpServletRequest httpReq) {
        if (request.getPendingActionId() == null) {
            return IntentResponse.error("pendingActionId is required.");
        }
        if (request.getConfirm() == null) {
            return IntentResponse.error("confirm (true|false) is required.");
        }
        return orchestrator.handle(applyPrincipal(request, httpReq));
    }

    /**
     * Overwrite caller identity fields on the request with the server-derived
     * principal. USER_JWT and ANONYMOUS callers cannot self-report roles;
     * only INTERNAL_PROXY (the Portfolio Next.js proxy, itself authenticated
     * against Supabase) may set identity via the body.
     */
    private static IntentRequest applyPrincipal(IntentRequest req, HttpServletRequest httpReq) {
        AuthenticatedPrincipal p = AuthenticatedPrincipal.of(httpReq);
        switch (p.source()) {
            case USER_JWT -> {
                req.setUserId(p.userId());
                req.setUserEmail(p.email());
                req.setUserRoles(p.rolesCsv());
            }
            case ANONYMOUS -> {
                req.setUserId(null);
                req.setUserEmail(null);
                req.setUserRoles(null);
            }
            case INTERNAL_PROXY -> {
                // Trust body — the proxy already verified the end user.
            }
        }
        return req;
    }
}
