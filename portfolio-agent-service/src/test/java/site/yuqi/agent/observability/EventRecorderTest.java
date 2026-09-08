package site.yuqi.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.yuqi.ai.contracts.event.PlatformEvent;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EventRecorderTest {
    @Test void eventsRemainCorrelatedWithoutThreadLocalContext() throws Exception {
        var repository = mock(OutboxRepository.class);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var recorder = new EventRecorder(repository, mapper);
        var run = UUID.randomUUID();
        OperationContext.clear();
        recorder.record(PlatformEvent.now("agent_run.started").runId(run).build());
        recorder.record(PlatformEvent.now("retrieval.completed").runId(run).build());
        var json = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).insert(anyString(), json.capture());
        for (String event : json.getAllValues()) {
            assertThat(mapper.readTree(event).path("traceId").asText()).isEqualTo(run.toString().replace("-", ""));
        }
    }
}
