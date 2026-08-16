package site.yuqi.agent.workflow;

import org.junit.jupiter.api.Test;
import site.yuqi.agent.observability.EventRecorder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentRunLifecycleTest {

    private final AgentRunLifecycle lifecycle = new AgentRunLifecycle(mock(EventRecorder.class));

    @Test
    void acceptsDefinedTransitionsAndRemovesCompletedRun() {
        UUID runId = UUID.randomUUID();
        lifecycle.begin(runId);
        lifecycle.transition(runId, AgentRunState.ADMITTED);
        lifecycle.transition(runId, AgentRunState.GUARDING);
        lifecycle.transition(runId, AgentRunState.PLANNING);

        assertThat(lifecycle.current(runId)).isEqualTo(AgentRunState.PLANNING);

        lifecycle.complete(runId, "completed");
        assertThat(lifecycle.current(runId)).isNull();
    }

    @Test
    void rejectsUndefinedTransitionWithoutCorruptingState() {
        UUID runId = UUID.randomUUID();
        lifecycle.begin(runId);
        lifecycle.transition(runId, AgentRunState.GENERATING);

        assertThat(lifecycle.current(runId)).isEqualTo(AgentRunState.RECEIVED);
    }
}
