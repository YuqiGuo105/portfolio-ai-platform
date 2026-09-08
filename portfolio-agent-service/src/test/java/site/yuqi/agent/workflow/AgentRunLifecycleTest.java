package site.yuqi.agent.workflow;

import org.junit.jupiter.api.Test;
import site.yuqi.agent.observability.EventRecorder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AgentRunLifecycleTest {

    private final EventRecorder recorder = mock(EventRecorder.class);
    private final AgentRunLifecycle lifecycle = new AgentRunLifecycle(recorder);

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
        assertThatThrownBy(() -> lifecycle.transition(runId, AgentRunState.GENERATING))
                .isInstanceOf(IllegalStateException.class);

        assertThat(lifecycle.current(runId)).isEqualTo(AgentRunState.RECEIVED);
    }

    @Test
    void duplicateBeginCannotRewindAnActiveRun() {
        UUID runId = UUID.randomUUID();
        lifecycle.begin(runId);
        lifecycle.transition(runId, AgentRunState.ADMITTED);
        lifecycle.begin(runId);
        assertThat(lifecycle.current(runId)).isEqualTo(AgentRunState.ADMITTED);
        verify(recorder, times(2)).record(any());
    }

    @Test
    void repeatedCompletionIsIdempotentAndLateTransitionsFail() {
        UUID runId = UUID.randomUUID();
        lifecycle.begin(runId);
        lifecycle.complete(runId, "failed");
        lifecycle.complete(runId, "completed");
        verify(recorder, times(2)).record(any());
        assertThatThrownBy(() -> lifecycle.transition(runId, AgentRunState.ADMITTED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recordingFailureCannotLeakOrResetState() {
        doThrow(new IllegalStateException("recorder unavailable")).when(recorder).record(any());
        UUID runId = UUID.randomUUID();
        lifecycle.begin(runId);
        lifecycle.transition(runId, AgentRunState.ADMITTED);
        assertThat(lifecycle.current(runId)).isEqualTo(AgentRunState.ADMITTED);
        lifecycle.complete(runId, "failed");
        assertThat(lifecycle.current(runId)).isNull();
    }
}
