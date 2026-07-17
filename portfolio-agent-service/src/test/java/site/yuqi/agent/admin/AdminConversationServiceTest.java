package site.yuqi.agent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminConversationServiceTest {

    private final AdminConversationEventRepository repository = mock(AdminConversationEventRepository.class);
    private final AdminConversationService service =
            new AdminConversationService(repository, new ObjectMapper());

    @Test
    void aggregatesNestedOutboxEventsIntoConversationRunsAndSummary() {
        Instant base = Instant.parse("2026-07-17T01:00:00Z");
        List<AdminConversationEventRepository.EventRow> rows = List.of(
                row("agent_run", base, """
                        {"eventType":"agent_run.started","timestamp":"2026-07-17T01:00:00Z",
                         "runId":"run-1","status":"running","payload":{"question":"Recent visitors?",
                         "sessionId":"session-1","conversationId":"conversation-1"}}
                        """),
                row("retrieval", base.plusMillis(100), """
                        {"eventType":"retrieval.completed","timestamp":"2026-07-17T01:00:00.100Z",
                         "runId":"run-1","status":"success","latencyMs":100,
                         "payload":{"returnedChunks":3,"zeroHit":false}}
                        """),
                row("answer", base.plusMillis(900), """
                        {"eventType":"answer.generated","timestamp":"2026-07-17T01:00:00.900Z",
                         "runId":"run-1","status":"answered","latencyMs":900,
                         "payload":{"answer":"There were 12 visits.","route":"MCP_TOOL"}}
                        """),
                row("agent_run", base.plusSeconds(1), """
                        {"eventType":"agent_run.completed","timestamp":"2026-07-17T01:00:01Z",
                         "runId":"run-1","status":"completed","latencyMs":1000,
                         "payload":{"finalStatus":"completed"}}
                        """));
        when(repository.findRunEvents(any(Instant.class), anyString(), anyInt())).thenReturn(rows);

        AdminConversationService.ConversationResponse response = service.list("visitors", 168, 100);

        assertThat(response.total()).isEqualTo(1);
        assertThat(response.summary().runs()).isEqualTo(1);
        assertThat(response.summary().completed()).isEqualTo(1);
        assertThat(response.summary().blocked()).isZero();
        assertThat(response.summary().averageLatencyMs()).isEqualTo(1000);
        assertThat(response.items()).singleElement().satisfies(run -> {
            assertThat(run.question()).isEqualTo("Recent visitors?");
            assertThat(run.answer()).isEqualTo("There were 12 visits.");
            assertThat(run.route()).isEqualTo("MCP_TOOL");
            assertThat(run.status()).isEqualTo("completed");
            assertThat(run.steps()).singleElement().satisfies(step -> {
                assertThat(step.type()).isEqualTo("retrieval.completed");
                assertThat(step.latencyMs()).isEqualTo(100);
                assertThat(step.detail()).containsEntry("returnedChunks", 3);
            });
        });
    }

    @Test
    void reportsBlockedRunsSeparately() {
        Instant base = Instant.parse("2026-07-17T01:00:00Z");
        when(repository.findRunEvents(any(Instant.class), anyString(), anyInt())).thenReturn(List.of(
                row("agent_run", base, """
                        {"eventType":"agent_run.started","runId":"run-2","status":"running",
                         "payload":{"question":"unsafe"}}
                        """),
                row("answer", base.plusMillis(20), """
                        {"eventType":"answer.blocked","runId":"run-2","status":"blocked",
                         "latencyMs":20,"payload":{"answer":"Cannot help.","route":"BLOCKED"}}
                        """),
                row("agent_run", base.plusMillis(25), """
                        {"eventType":"agent_run.completed","runId":"run-2","status":"blocked",
                         "latencyMs":25,"payload":{"finalStatus":"blocked"}}
                        """)));

        AdminConversationService.ConversationResponse response = service.list("", 24, 10);

        assertThat(response.summary().blocked()).isEqualTo(1);
        assertThat(response.summary().completed()).isZero();
        assertThat(response.summary().averageLatencyMs()).isNull();
    }

    @Test
    void doesNotCountForbiddenRunsAsCompleted() {
        Instant base = Instant.parse("2026-07-17T01:00:00Z");
        when(repository.findRunEvents(any(Instant.class), anyString(), anyInt())).thenReturn(List.of(
                row("agent_run", base, """
                        {"eventType":"agent_run.started","runId":"run-forbidden","status":"running",
                         "payload":{"question":"Run an unauthorized tool"}}
                        """),
                row("agent_run", base.plusMillis(50), """
                        {"eventType":"agent_run.completed","runId":"run-forbidden","status":"forbidden",
                         "latencyMs":50,"payload":{"finalStatus":"forbidden"}}
                        """)));

        AdminConversationService.ConversationResponse response = service.list("", 24, 10);

        assertThat(response.summary().runs()).isEqualTo(1);
        assertThat(response.summary().completed()).isZero();
        assertThat(response.summary().blocked()).isZero();
        assertThat(response.summary().averageLatencyMs()).isNull();
    }

    @Test
    void treatsPlannerOutcomeAsCompletedLifecycleAndRoute() {
        Instant base = Instant.parse("2026-07-17T01:00:00Z");
        when(repository.findRunEvents(any(Instant.class), anyString(), anyInt())).thenReturn(List.of(
                row("agent_run", base, """
                        {"eventType":"agent_run.started","runId":"run-3","status":"running",
                         "payload":{"question":"Can you clarify?"}}
                        """),
                row("agent_run", base.plusMillis(40), """
                        {"eventType":"agent_run.completed","runId":"run-3","status":"clarify",
                         "latencyMs":40,"payload":{"finalStatus":"clarify"}}
                        """)));

        AdminConversationService.ConversationResponse response = service.list("", 24, 10);

        assertThat(response.summary().completed()).isEqualTo(1);
        assertThat(response.summary().averageLatencyMs()).isEqualTo(40);
        assertThat(response.items()).singleElement().satisfies(run -> {
            assertThat(run.completedAt()).isNotNull();
            assertThat(run.status()).isEqualTo("clarify");
            assertThat(run.route()).isEqualTo("CLARIFY");
        });
    }

    private static AdminConversationEventRepository.EventRow row(
            String category, Instant createdAt, String payload) {
        return new AdminConversationEventRepository.EventRow(
                UUID.randomUUID(), category, payload, createdAt);
    }
}
