package site.yuqi.agent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.Set;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AttachmentRegistryTest {
    @Test
    void chatActivityCannotPostponeDeletionRetries() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        AttachmentRegistry registry = new AttachmentRegistry(redis, mapper);
        ReflectionTestUtils.setField(registry, "leaseSeconds", 1800L);
        ReflectionTestUtils.setField(registry, "cleanupRecordTtlSeconds", 86400L);
        AttachmentRecord record = AttachmentRecord.builder().id("test").conversationId("conv")
                .status(AttachmentRecord.Status.DELETE_RETRY).expiresAt(Instant.now().plusSeconds(60)).build();
        when(redis.opsForSet().members("agent:conversation-attachments:conv")).thenReturn(Set.of("test"));
        when(redis.opsForValue().get("agent:attachment:test")).thenReturn(mapper.writeValueAsString(record));
        registry.touchConversation("conv");
        verify(redis.opsForZSet(), never()).add(anyString(), anyString(), anyDouble());
    }
}
