package site.yuqi.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Redis-backed pending-command store. Staging is atomic and consumption uses a
 * Lua compare-and-delete operation so a confirmed write can execute at most
 * once across Cloud Run instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingActionStore {

    private static final String KEY_PREFIX = "agent:pending-action:";
    private static final String SESSION_MISMATCH = "__SESSION_MISMATCH__";

    private static final DefaultRedisScript<Long> STAGE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('HSET', KEYS[1],
                'sessionId', ARGV[1],
                'payload', ARGV[2])
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
            return 1
            """, Long.class);

    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local owner = redis.call('HGET', KEYS[1], 'sessionId')
            if not owner then
                return nil
            end
            if ARGV[1] ~= '' and owner ~= ARGV[1] then
                return '__SESSION_MISMATCH__'
            end
            local payload = redis.call('HGET', KEYS[1], 'payload')
            if not payload then
                return nil
            end
            redis.call('DEL', KEYS[1])
            return payload
            """, String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.intent.pending-action.ttl-seconds:300}")
    private long ttlSeconds;

    public PendingAction stage(
            String sessionId,
            String userId,
            ToolDefinition tool,
            java.util.Map<String, Object> resolvedArgs,
            String previewMessage) {
        Instant now = Instant.now();
        PendingAction action = PendingAction.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(sessionId)
                .userId(userId)
                .toolName(tool.name())
                .intent(tool.intent())
                .riskLevel(tool.riskLevel())
                .resolvedArguments(resolvedArgs)
                .previewMessage(previewMessage)
                .createdAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .build();
        try {
            Long stored = redisTemplate.execute(
                    STAGE_SCRIPT,
                    List.of(key(action.getId())),
                    nonNull(sessionId),
                    objectMapper.writeValueAsString(action),
                    Long.toString(ttlSeconds));
            if (!Long.valueOf(1L).equals(stored)) {
                throw new IllegalStateException("Redis did not acknowledge the pending action");
            }
            return action;
        } catch (Exception e) {
            log.error("Pending action stage failed actionId={}", action.getId(), e);
            throw new IllegalStateException("Confirmation state is temporarily unavailable", e);
        }
    }

    public Optional<PendingAction> consume(String id, String sessionId) {
        if (id == null || id.isBlank()) return Optional.empty();
        try {
            String payload = redisTemplate.execute(
                    CONSUME_SCRIPT,
                    List.of(key(id)),
                    nonNull(sessionId));
            if (payload == null || payload.isBlank()) return Optional.empty();
            if (SESSION_MISMATCH.equals(payload)) {
                log.warn("PendingAction {} session mismatch; rejecting without consuming", id);
                return Optional.empty();
            }
            PendingAction action = objectMapper.readValue(payload, PendingAction.class);
            if (Instant.now().isAfter(action.getExpiresAt())) return Optional.empty();
            return Optional.of(action);
        } catch (Exception e) {
            log.error("Pending action consume failed actionId={}", id, e);
            throw new IllegalStateException("Confirmation state is temporarily unavailable", e);
        }
    }

    private static String key(String id) {
        return KEY_PREFIX + id;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
