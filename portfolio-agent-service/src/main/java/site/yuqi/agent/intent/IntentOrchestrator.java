package site.yuqi.agent.intent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Top-level intent pipeline.
 *
 * <pre>
 *   IntentRequest
 *     ├── (a) pendingActionId present  → consume + execute or cancel
 *     └── (b) fresh utterance
 *           ↓
 *         classifier.classify()                          (LLM, untrusted)
 *           ↓
 *         validator.validate()                           (reject / clarify / general-chat)
 *           ↓                                            (escalate if borderline)
 *         entityResolver.resolve()                       (read-only gateway calls)
 *           ↓
 *         policyGuard.check()                            (roles + confirmation gate)
 *           ↓
 *         if requiresConfirmation → stage + return CONFIRMATION_REQUIRED
 *         else                    → toolExecutor.execute() and return OK
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentOrchestrator {

    private final IntentClassifier classifier;
    private final IntentValidator validator;
    private final EntityResolver entityResolver;
    private final PolicyGuard policyGuard;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final PendingActionStore pendingActionStore;
    private final AuditService auditService;

    public IntentResponse handle(IntentRequest request) {
        // ── (a) Confirmation leg ────────────────────────────────────────
        if (request.getPendingActionId() != null) {
            return handleConfirmation(request);
        }

        // ── (b) Fresh utterance ────────────────────────────────────────
        IntentResult intent;
        try {
            intent = classifier.classify(request);
        } catch (IntentClassificationException e) {
            auditService.logError(request, "intent.classify", e);
            return IntentResponse.error("Could not classify request: " + e.getMessage());
        }
        auditService.logClassification(request, intent);

        return handleClassified(request, intent, true);
    }

    public IntentResponse handlePreclassified(IntentRequest request, IntentResult intent) {
        if (request.getPendingActionId() != null) {
            return handleConfirmation(request);
        }
        auditService.logClassification(request, intent);
        return handleClassified(request, intent, false);
    }

    private IntentResponse handleClassified(IntentRequest request, IntentResult intent, boolean allowEscalation) {
        IntentValidator.ValidationResult v = validator.validate(intent);

        // Optional escalation for borderline READ_ONLY confidence.
        if (allowEscalation
                && v.getStatus() == IntentValidator.Status.CLARIFY
                && intent.intent() != IntentType.CLARIFICATION_NEEDED
                && intent.intent() != IntentType.UNKNOWN
                && intent.targetTool() != null) {
            try {
                IntentResult second = classifier.escalate(request, intent);
                if (second != intent) {
                    auditService.logClassification(request, second);
                    intent = second;
                    v = validator.validate(intent);
                }
            } catch (IntentClassificationException e) {
                log.debug("Escalation skipped: {}", e.toString());
            }
        }

        switch (v.getStatus()) {
            case GENERAL_CHAT -> {
                return IntentResponse.generalChat(
                        "I can answer general questions, but for portfolio actions ask me to "
                                + "search, list, or update something specific.");
            }
            case REJECT -> {
                auditService.logValidationFailure(request, intent, v.getMessage());
                return IntentResponse.error(v.getMessage());
            }
            case CLARIFY -> {
                auditService.logClarification(request, intent, v.getMessage());
                return IntentResponse.ask(v.getMessage());
            }
            case EXECUTE -> { /* continue below */ }
        }

        ToolDefinition tool = v.getTool();

        // ── Entity resolution ───────────────────────────────────────────
        EntityResolver.EntityResolutionResult resolved = entityResolver.resolve(intent, tool, request);
        if (resolved.needsClarification()) {
            auditService.logClarification(request, intent, resolved.getQuestion());
            return resolved.getOptions() == null
                    ? IntentResponse.ask(resolved.getQuestion())
                    : IntentResponse.ask(resolved.getQuestion(),
                            resolved.getOptions().stream()
                                    .map(o -> (java.util.Map<String, Object>) o).toList());
        }

        // ── Policy / permission gate ────────────────────────────────────
        PolicyGuard.PolicyDecision policy = policyGuard.check(tool, resolved.getResolvedArguments(), request);
        if (!policy.isAllowed()) {
            auditService.logValidationFailure(request, intent, policy.getReason());
            return IntentResponse.forbidden(policy.getReason());
        }

        // ── Confirmation gate for non-read tools ────────────────────────
        if (policy.isRequiresConfirmation()) {
            PendingAction action = pendingActionStore.stage(
                    request.getSessionId(),
                    request.getUserId(),
                    tool,
                    resolved.getResolvedArguments(),
                    policy.getPreview());
            auditService.logConfirmationStaged(request, action);
            return IntentResponse.confirmation(policy.getPreview(), action.getId(), intent);
        }

        // ── Direct execute (READ_ONLY only) ─────────────────────────────
        return toolExecutor.execute(request, tool, resolved.getResolvedArguments(), intent);
    }

    private IntentResponse handleConfirmation(IntentRequest request) {
        Optional<PendingAction> opt = pendingActionStore.consume(
                request.getPendingActionId(), request.getSessionId());
        if (opt.isEmpty()) {
            return IntentResponse.error(
                    "Pending action " + request.getPendingActionId() + " not found, expired, or session-mismatched.");
        }
        PendingAction action = opt.get();

        if (request.getConfirm() == null || !request.getConfirm()) {
            return IntentResponse.ask("Cancelled. The action was not performed.");
        }

        ToolDefinition tool = toolRegistry.find(action.getToolName())
                .orElseThrow(() -> new IllegalStateException(
                        "Pending action references unknown tool: " + action.getToolName()));

        // Synthesize an IntentResult echo for the OK payload's debug field.
        IntentResult echo = new IntentResult(
                action.getIntent(),
                action.getToolName(),
                1.0,
                null,
                null,
                action.getResolvedArguments(),
                action.getRiskLevel(),
                true,
                java.util.List.of(),
                null);

        return toolExecutor.execute(request, tool, action.getResolvedArguments(), echo);
    }
}
