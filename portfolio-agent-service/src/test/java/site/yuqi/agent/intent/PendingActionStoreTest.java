package site.yuqi.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PendingActionStoreTest {

    private StringRedisTemplate redis;
    private ObjectMapper objectMapper;
    private PendingActionStore store;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        store = new PendingActionStore(redis, objectMapper);
        ReflectionTestUtils.setField(store, "ttlSeconds", 300L);
    }

    @Test
    void stagesSerializedCommandWithTtl() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenReturn(1L);

        PendingAction action = store.stage("session-1", "user-1", tool(), Map.of("id", 42), "preview");

        assertThat(action.getSessionId()).isEqualTo("session-1");
        assertThat(action.getExpiresAt()).isAfter(action.getCreatedAt());
    }

    @Test
    void consumesSerializedCommandReturnedByAtomicScript() throws Exception {
        PendingAction action = PendingAction.builder()
                .id("action-1")
                .sessionId("session-1")
                .userId("user-1")
                .toolName("article.publish")
                .intent(IntentType.UNKNOWN)
                .riskLevel(RiskLevel.RISKY_WRITE)
                .resolvedArguments(Map.of("id", 42))
                .createdAt(java.time.Instant.now())
                .expiresAt(java.time.Instant.now().plusSeconds(300))
                .build();
        when(redis.execute(any(RedisScript.class), anyList(), eq("session-1")))
                .thenReturn(objectMapper.writeValueAsString(action));

        assertThat(store.consume("action-1", "session-1"))
                .get()
                .extracting(PendingAction::getToolName)
                .isEqualTo("article.publish");
    }

    @Test
    void sessionMismatchDoesNotReturnCommand() {
        when(redis.execute(any(RedisScript.class), anyList(), eq("session-2")))
                .thenReturn("__SESSION_MISMATCH__");

        assertThat(store.consume("action-1", "session-2")).isEmpty();
    }

    @Test
    void failsClosedWhenRedisIsUnavailable() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new IllegalStateException("offline"));

        assertThatThrownBy(() -> store.stage("session-1", "user-1", tool(), Map.of(), "preview"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("temporarily unavailable");
    }

    private static ToolDefinition tool() {
        return new ToolDefinition(
                "article.publish",
                IntentType.UNKNOWN,
                "publish",
                RiskLevel.RISKY_WRITE,
                true,
                Set.of(),
                Set.of());
    }
}
