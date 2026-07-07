package site.yuqi.agent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import site.yuqi.agent.intent.IntentResponse;
import site.yuqi.agent.intent.IntentResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MemoryWriter {

    private final RedisConversationStore redisStore;
    private final MemorySanitizer sanitizer;
    private final ConversationCompactor compactor;
    private final ObjectMapper objectMapper;

    public void writeTurnPair(String conversationId,
                              String userText,
                              String assistantText,
                              String route,
                              IntentResponse response) {
        if (conversationId == null || conversationId.isBlank()) return;
        String targetTool = targetTool(response);
        Map<String, Object> toolContext = toolContext(response);
        MemoryTurn userTurn = turn("user", userText, route, null, null);
        MemoryTurn assistantTurn = turn("assistant", assistantText, route, targetTool, toolContext);
        Map<String, Object> pendingAction = pendingAction(response);
        boolean clearPending = response == null || !"CONFIRMATION_REQUIRED".equals(response.getType());
        redisStore.append(conversationId, List.of(userTurn, assistantTurn), pendingAction, clearPending);
        compactor.compactIfNeeded(conversationId, false);
    }

    public void writeTurnPair(String conversationId,
                              String userText,
                              String assistantText,
                              String route,
                              Map<String, Object> toolContext) {
        if (conversationId == null || conversationId.isBlank()) return;
        MemoryTurn userTurn = turn("user", userText, route, null, null);
        MemoryTurn assistantTurn = turn("assistant", assistantText, route,
                toolContext == null ? null : String.valueOf(toolContext.getOrDefault("targetTool", "")),
                toolContext);
        redisStore.append(conversationId, List.of(userTurn, assistantTurn), null, true);
        compactor.compactIfNeeded(conversationId, false);
    }

    private MemoryTurn turn(String role,
                            String content,
                            String route,
                            String targetTool,
                            Map<String, Object> toolContext) {
        String compact = sanitizer.truncate(content, 4_000);
        return MemoryTurn.builder()
                .role(role)
                .content(compact)
                .route(route)
                .targetTool(targetTool == null || targetTool.isBlank() ? null : targetTool)
                .toolContext(toolContext == null ? null : sanitizer.sanitizeMap(toolContext))
                .approxTokens(approxTokens(compact))
                .createdAt(Instant.now())
                .build();
    }

    private Map<String, Object> toolContext(IntentResponse response) {
        if (response == null || response.getIntent() == null || response.getIntent().targetTool() == null) {
            return null;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        IntentResult intent = response.getIntent();
        ctx.put("targetTool", intent.targetTool());
        ctx.put("intent", intent.intent().name());
        ctx.put("entities", sanitizer.sanitize(intent.entities()));
        if (response.getResult() != null) {
            ctx.put("result", sanitizer.sanitize(response.getResult()));
        }
        if (response.getPendingActionId() != null) {
            ctx.put("pendingActionId", response.getPendingActionId());
        }
        return ctx;
    }

    private Map<String, Object> pendingAction(IntentResponse response) {
        if (response == null || !"CONFIRMATION_REQUIRED".equals(response.getType())) return null;
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("pendingActionId", response.getPendingActionId());
        pending.put("message", response.getMessage());
        if (response.getIntent() != null) {
            pending.put("targetTool", response.getIntent().targetTool());
            pending.put("intent", response.getIntent().intent().name());
            pending.put("entities", sanitizer.sanitize(response.getIntent().entities()));
        }
        return pending;
    }

    private String targetTool(IntentResponse response) {
        return response != null && response.getIntent() != null ? response.getIntent().targetTool() : null;
    }

    private int approxTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    public String responseText(IntentResponse response) {
        if (response == null) return "";
        if (response.getMessage() != null && !response.getMessage().isBlank()) return response.getMessage();
        if (response.getResult() == null) return "";
        try {
            return objectMapper.writeValueAsString(sanitizer.sanitize(response.getResult()));
        } catch (Exception e) {
            return String.valueOf(response.getResult());
        }
    }
}
