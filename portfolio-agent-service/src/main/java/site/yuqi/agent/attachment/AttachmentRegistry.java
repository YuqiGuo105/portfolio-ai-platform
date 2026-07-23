package site.yuqi.agent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttachmentRegistry {

    private static final String RECORD_PREFIX = "agent:attachment:";
    private static final String CONVERSATION_PREFIX = "agent:conversation-attachments:";
    private static final String EXPIRY_INDEX = "agent:attachment-expiry";
    private static final String RATE_PREFIX = "agent:attachment-rate:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${agent.attachments.lease-seconds:${agent.memory.ttl-seconds:1800}}")
    private long leaseSeconds;

    @Value("${agent.attachments.cleanup-record-ttl-seconds:86400}")
    private long cleanupRecordTtlSeconds;

    @Value("${agent.attachments.max-uploads-per-hour:8}")
    private long maxUploadsPerHour;

    @Value("${agent.attachments.max-global-uploads-per-day:250}")
    private long maxGlobalUploadsPerDay;

    public void enforceIssueRate(String conversationId) {
        String globalKey = RATE_PREFIX + "global:"
                + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.now().atZone(ZoneOffset.UTC));
        Long globalCount = redisTemplate.opsForValue().increment(globalKey);
        if (globalCount != null && globalCount == 1L) {
            redisTemplate.expire(globalKey, Duration.ofDays(2));
        }
        if (globalCount != null && globalCount > maxGlobalUploadsPerDay) {
            throw new IllegalStateException("Daily attachment upload capacity reached");
        }

        String key = RATE_PREFIX + conversationId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofHours(1));
        }
        if (count != null && count > maxUploadsPerHour) {
            throw new IllegalStateException("Attachment upload rate limit exceeded");
        }
    }

    public void register(AttachmentRecord record) {
        Instant now = Instant.now();
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        record.setExpiresAt(now.plusSeconds(leaseSeconds));
        save(record);
        String conversationKey = conversationKey(record.getConversationId());
        redisTemplate.opsForSet().add(conversationKey, record.getId());
        redisTemplate.expire(conversationKey,
                Duration.ofSeconds(leaseSeconds + cleanupRecordTtlSeconds));
    }

    public AttachmentRecord requireOwned(String attachmentId, String conversationId) {
        AttachmentRecord record = load(attachmentId);
        if (record == null || !constantEquals(record.getConversationId(), conversationId)) {
            throw new IllegalArgumentException("Attachment does not belong to this conversation");
        }
        return record;
    }

    public AttachmentRecord load(String attachmentId) {
        try {
            String raw = redisTemplate.opsForValue().get(recordKey(attachmentId));
            return raw == null ? null : objectMapper.readValue(raw, AttachmentRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("Attachment registry is unavailable", e);
        }
    }

    public void markReady(AttachmentRecord record, long storedSizeBytes, String actualMimeType) {
        record.setStoredSizeBytes(storedSizeBytes);
        record.setMimeType(actualMimeType);
        record.setStatus(AttachmentRecord.Status.READY);
        touch(record);
    }

    public void markParsed(AttachmentRecord record, String parsedContext) {
        record.setParsedContext(parsedContext);
        record.setStatus(AttachmentRecord.Status.PARSED);
        touch(record);
    }

    public void touchConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        Set<String> ids = redisTemplate.opsForSet().members(conversationKey(conversationId));
        if (ids == null || ids.isEmpty()) return;
        for (String id : ids) {
            AttachmentRecord record = load(id);
            if (record != null && constantEquals(record.getConversationId(), conversationId)) {
                touch(record);
            }
        }
        redisTemplate.expire(conversationKey(conversationId),
                Duration.ofSeconds(leaseSeconds + cleanupRecordTtlSeconds));
    }

    public long conversationAttachmentCount(String conversationId) {
        Long size = redisTemplate.opsForSet().size(conversationKey(conversationId));
        return size == null ? 0L : size;
    }

    public void expireConversationNow(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) return;
        Set<String> ids = redisTemplate.opsForSet().members(conversationKey(conversationId));
        if (ids == null) return;
        Instant now = Instant.now();
        for (String id : ids) {
            AttachmentRecord record = load(id);
            if (record == null) continue;
            record.setExpiresAt(now);
            save(record);
        }
    }

    public List<String> dueAttachmentIds(int limit) {
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(
                EXPIRY_INDEX, 0, Instant.now().toEpochMilli(), 0, limit);
        return ids == null ? List.of() : new ArrayList<>(ids);
    }

    public void retryDeletion(AttachmentRecord record, String error, Duration delay) {
        record.setStatus(AttachmentRecord.Status.DELETE_RETRY);
        record.setDeleteAttempts(record.getDeleteAttempts() + 1);
        record.setLastDeleteError(error);
        record.setExpiresAt(Instant.now().plus(delay));
        save(record);
    }

    public void remove(AttachmentRecord record) {
        redisTemplate.delete(recordKey(record.getId()));
        redisTemplate.opsForSet().remove(conversationKey(record.getConversationId()), record.getId());
        redisTemplate.opsForZSet().remove(EXPIRY_INDEX, record.getId());
    }

    public void removeExpiryIndex(String attachmentId) {
        redisTemplate.opsForZSet().remove(EXPIRY_INDEX, attachmentId);
    }

    private void touch(AttachmentRecord record) {
        record.setExpiresAt(Instant.now().plusSeconds(leaseSeconds));
        save(record);
    }

    private void save(AttachmentRecord record) {
        try {
            record.setUpdatedAt(Instant.now());
            redisTemplate.opsForValue().set(
                    recordKey(record.getId()),
                    objectMapper.writeValueAsString(record),
                    Duration.ofSeconds(leaseSeconds + cleanupRecordTtlSeconds));
            redisTemplate.opsForZSet().add(
                    EXPIRY_INDEX, record.getId(), record.getExpiresAt().toEpochMilli());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to persist attachment lease", e);
        }
    }

    private String recordKey(String id) {
        return RECORD_PREFIX + id;
    }

    private String conversationKey(String conversationId) {
        return CONVERSATION_PREFIX + conversationId;
    }

    private boolean constantEquals(String left, String right) {
        if (left == null || right == null) return false;
        return MessageDigestSupport.equals(left, right);
    }

    private static final class MessageDigestSupport {
        private static boolean equals(String left, String right) {
            return java.security.MessageDigest.isEqual(
                    left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
