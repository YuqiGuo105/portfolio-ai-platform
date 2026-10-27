package site.yuqi.agent.budget;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatCapabilityBudgetTest {
    @Test
    void disablingBudgetDoesNotReenableUnsupportedDeepFeatures() {
        var redis = mock(StringRedisTemplate.class);
        var service = spy(new ChatBudgetService(redis));
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "deepPathEnabled", false);
        doReturn(null).when(service).snapshot();
        assertThat(service.evaluateHighCostPath().allowed()).isFalse();
        verifyNoInteractions(redis);
    }
}
