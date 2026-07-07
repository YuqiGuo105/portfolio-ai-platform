package site.yuqi.agent.conversation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.agent.observability.EventRecorder;
import site.yuqi.ai.contracts.event.EventTypes;
import site.yuqi.ai.contracts.event.PlatformEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Conversation persistence — writes conversation/message/run state to Aiven PG.
 * Also records structured events to the outbox for OpenSearch sync.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final JdbcTemplate jdbc;
    private final EventRecorder eventRecorder;

    @Transactional
    public UUID createConversation(String userId, String channel) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into conversation (id, user_id, channel, status, created_at, updated_at)
                values (?, ?, ?, 'active', now(), now())
                """, id, userId, channel != null ? channel : "api");
        return id;
    }

    @Transactional
    public UUID saveMessage(UUID conversationId, String role, String content, Map<String, Object> metadata) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into message (id, conversation_id, role, content, metadata_json, created_at)
                values (?, ?, ?, ?, ?::jsonb, now())
                """, id, conversationId, role, content,
                metadata != null ? toJson(metadata) : "{}");
        return id;
    }

    @Transactional
    public UUID startRun(UUID conversationId, UUID userMessageId) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                insert into agent_run (id, conversation_id, user_message_id, status, started_at)
                values (?, ?, ?, 'running', now())
                """, runId, conversationId, userMessageId);

        eventRecorder.record(PlatformEvent.now(EventTypes.AGENT_RUN_STARTED)
                .conversationId(conversationId)
                .runId(runId)
                .service("agent-runtime")
                .status("running")
                .build());

        return runId;
    }

    @Transactional
    public void completeRun(UUID runId, String status, String intent, Double confidence) {
        jdbc.update("""
                update agent_run set status = ?, intent = ?, confidence = ?, completed_at = now()
                where id = ?
                """, status, intent, confidence, runId);
    }

    @Transactional
    public void recordStep(UUID runId, String stepType, String status,
                           Map<String, Object> input, Map<String, Object> output, int latencyMs) {
        UUID stepId = UUID.randomUUID();
        jdbc.update("""
                insert into agent_step (id, run_id, step_type, status, input_json, output_json, latency_ms, created_at)
                values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, now())
                """, stepId, runId, stepType, status,
                toJson(input), toJson(output), latencyMs);

        eventRecorder.record(PlatformEvent.now(EventTypes.AGENT_STEP_COMPLETED)
                .runId(runId)
                .service("agent-runtime")
                .latencyMs(latencyMs)
                .status(status)
                .payload(Map.of("stepType", stepType))
                .build());
    }

    @Transactional
    public void recordModelCall(UUID runId, String model, String operation,
                                int inputTokens, int outputTokens, int latencyMs, String status) {
        jdbc.update("""
                insert into model_call_log (id, run_id, model, operation, input_tokens, output_tokens, latency_ms, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), runId, model, operation, inputTokens, outputTokens, latencyMs, status);

        eventRecorder.record(PlatformEvent.now(EventTypes.MODEL_CALL_COMPLETED)
                .runId(runId)
                .service("model-gateway")
                .latencyMs(latencyMs)
                .status(status)
                .payload(Map.of("model", model, "operation", operation,
                        "inputTokens", inputTokens, "outputTokens", outputTokens))
                .build());
    }

    @Transactional
    public void recordToolCall(UUID runId, String toolName, String userId, String riskLevel,
                               String status, int latencyMs) {
        jdbc.update("""
                insert into tool_call_log (id, run_id, tool_name, actor_user_id, risk_level, status, latency_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), runId, toolName, userId, riskLevel, status, latencyMs);

        eventRecorder.record(PlatformEvent.now(EventTypes.TOOL_CALL_COMPLETED)
                .runId(runId)
                .service("tool-gateway")
                .latencyMs(latencyMs)
                .status(status)
                .payload(Map.of("toolName", toolName, "riskLevel", riskLevel != null ? riskLevel : ""))
                .build());
    }

    @Transactional
    public void recordRetrieval(UUID runId, int topK, int keywordHits, int vectorHits,
                                int returnedChunks, boolean zeroHit, int latencyMs) {
        jdbc.update("""
                insert into retrieval_log (id, run_id, top_k, keyword_hits, vector_hits, returned_chunks, zero_hit, latency_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                """, UUID.randomUUID(), runId, topK, keywordHits, vectorHits, returnedChunks, zeroHit, latencyMs);

        eventRecorder.record(PlatformEvent.now(EventTypes.RETRIEVAL_COMPLETED)
                .runId(runId)
                .service("knowledge-service")
                .latencyMs(latencyMs)
                .status(zeroHit ? "zero_hit" : "ok")
                .payload(Map.of("topK", topK, "returnedChunks", returnedChunks, "zeroHit", zeroHit))
                .build());
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
