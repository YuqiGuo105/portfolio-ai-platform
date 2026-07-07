package site.yuqi.agent.conversation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationMemoryComponentsTest {

    @Test
    void conversationKeyIsStableOpaqueAndDeviceSensitive() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.8, 10.0.0.1");
        request.addHeader("X-CW-Device-Id", "device-a");
        request.addHeader("User-Agent", "Mozilla/5.0");

        String first = ConversationKey.derive(request, "session-1");
        String second = ConversationKey.derive(request, "session-1");

        assertThat(first)
                .isEqualTo(second)
                .startsWith("conv_")
                .doesNotContain("session-1", "device-a", "203.0.113.8");

        MockHttpServletRequest otherDevice = new MockHttpServletRequest();
        otherDevice.addHeader("X-Forwarded-For", "203.0.113.8");
        otherDevice.addHeader("X-CW-Device-Id", "device-b");

        assertThat(ConversationKey.derive(otherDevice, "session-1")).isNotEqualTo(first);
    }

    @Test
    void sanitizerRedactsSecretsBeforeMemoryWrite() {
        MemorySanitizer sanitizer = new MemorySanitizer();

        Map<String, Object> sanitized = sanitizer.sanitizeMap(Map.of(
                "apiKey", "secret-api-key",
                "safe", "aggregate analytics",
                "nested", Map.of("authorization", "Bearer token", "count", 7),
                "items", List.of(Map.of("otp", "123456", "page", "/blog"))));

        assertThat(sanitized).containsEntry("apiKey", "***");
        assertThat(sanitized).containsEntry("safe", "aggregate analytics");
        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) sanitized.get("nested");
        assertThat(nested)
                .containsEntry("authorization", "***")
                .containsEntry("count", 7);

        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<?>) sanitized.get("items")).getFirst();
        assertThat(item)
                .containsEntry("otp", "***")
                .containsEntry("page", "/blog");
    }

    @Test
    void contextLoaderUsesRedisMemoryAndIgnoresClientHistory() {
        RedisConversationStore store = mock(RedisConversationStore.class);
        MemorySanitizer sanitizer = new MemorySanitizer();
        ConversationContextLoader loader = new ConversationContextLoader(store, sanitizer);

        List<MemoryTurn> turns = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            turns.add(MemoryTurn.builder()
                    .role(i % 2 == 0 ? "assistant" : "user")
                    .content("redis turn " + i)
                    .createdAt(Instant.now())
                    .build());
        }

        ConversationMemory memory = ConversationMemory.builder()
                .conversationId("conv_test")
                .compactSummary(Map.of("userGoals", List.of("understand recent visitors")))
                .structuredState(Map.of("entities", Map.of("timeRange", "last_7_days")))
                .pendingAction(Map.of("pendingActionId", "pending-1"))
                .turns(turns)
                .build();
        when(store.load("conv_test")).thenReturn(memory);

        PlannerContext context = loader.load("conv_test", List.of(Map.of(
                "role", "user",
                "content", "frontend history should be ignored")));

        assertThat(context.recentMessages())
                .hasSize(6)
                .extracting(turn -> turn.get("content"))
                .containsExactly(
                        "redis turn 3",
                        "redis turn 4",
                        "redis turn 5",
                        "redis turn 6",
                        "redis turn 7",
                        "redis turn 8")
                .doesNotContain("frontend history should be ignored");
        assertThat(context.compactSummary()).containsKey("userGoals");
        assertThat(context.structuredState()).containsKey("entities");
        assertThat(context.pendingAction()).containsEntry("pendingActionId", "pending-1");
    }
}
