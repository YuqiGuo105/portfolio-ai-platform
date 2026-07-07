package site.yuqi.agent.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisConversationStore {

    private static final String KEY_PREFIX = "agent:conversation:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.memory.ttl-seconds:1800}")
    private long ttlSeconds;

    public ConversationMemory load(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ConversationMemory.empty("anonymous");
        }
        String key = key(conversationId);
        try {
            String raw = redisTemplate.opsForValue().get(key);
            redisTemplate.expire(key, Duration.ofSeconds(ttlSeconds));
            if (raw == null || raw.isBlank()) {
                return ConversationMemory.empty(conversationId);
            }
            ConversationMemory memory = objectMapper.readValue(raw, ConversationMemory.class);
            if (memory.getConversationId() == null) memory.setConversationId(conversationId);
            return memory;
        } catch (Exception e) {
            log.warn("Redis conversation load failed conversationId={}: {}", conversationId, e.toString());
            return ConversationMemory.empty(conversationId);
        }
    }

    public void save(ConversationMemory memory) {
        if (memory == null || memory.getConversationId() == null || memory.getConversationId().isBlank()) return;
        try {
            memory.setUpdatedAt(Instant.now());
            if (memory.getCreatedAt() == null) memory.setCreatedAt(memory.getUpdatedAt());
            redisTemplate.opsForValue().set(
                    key(memory.getConversationId()),
                    objectMapper.writeValueAsString(memory),
                    Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("Redis conversation save failed conversationId={}: {}", memory.getConversationId(), e.toString());
        }
    }

    public ConversationMemory append(String conversationId,
                                     List<MemoryTurn> turns,
                                     Map<String, Object> pendingAction,
                                     boolean clearPendingAction) {
        ConversationMemory memory = load(conversationId);
        if (memory.getTurns() == null) memory.setTurns(new ArrayList<>());
        if (turns != null) {
            memory.getTurns().addAll(turns);
            memory.setTotalTurns(memory.getTotalTurns() + turns.size());
        }
        if (clearPendingAction) {
            memory.setPendingAction(null);
        } else if (pendingAction != null && !pendingAction.isEmpty()) {
            memory.setPendingAction(pendingAction);
        }
        save(memory);
        return memory;
    }

    public List<String> scanConversationIds() {
        List<String> ids = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (key != null && key.startsWith(KEY_PREFIX)) {
                    ids.add(key.substring(KEY_PREFIX.length()));
                }
            }
        } catch (Exception e) {
            log.debug("Redis conversation scan skipped: {}", e.toString());
        }
        return ids;
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }
}
