package site.yuqi.agent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RunDiagnosticsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID run = UUID.randomUUID();

    private AdminConversationEventRepository.EventRow row(String type, String trace, Map<String, Object> payload) throws Exception {
        return new AdminConversationEventRepository.EventRow(UUID.randomUUID(), type.split("\\.")[0],
                mapper.writeValueAsString(Map.of("runId", run.toString(), "eventType", type, "traceId", trace, "payload", payload)), Instant.now());
    }

    @Test void reportsHistoricalProvenanceGapAndUnavailableSafety() throws Exception {
        var result = RunDiagnostics.describe(run, List.of(
                row("agent_run.started", "a", Map.of()),
                row("retrieval.completed", "b", Map.of("returnedChunks", 2)),
                row("safety.check_completed", "c", Map.of("category", "UNKNOWN")),
                row("agent_run.completed", "d", Map.of())), mapper);
        assertThat(result.get("signals").toString()).contains("RETRIEVAL_PROVENANCE_MISSING",
                "SAFETY_CHECK_UNAVAILABLE_OR_UNPARSEABLE", "TRACE_FRAGMENTED_USE_RUN_ID")
                .doesNotContain("TERMINAL_EVENT_NOT_RECORDED");
        assertThat(result.get("eventCount")).isEqualTo(4);
    }

    @Test void preservesSourcesButRedactsCredentials() throws Exception {
        var result = RunDiagnostics.describe(run, List.of(row("retrieval.completed", "a",
                Map.of("returnedChunks", 1, "sources", List.of(Map.of("chunkId", "c1", "title", "Public article")),
                        "nested", Map.of("accessToken", "not-for-output", "password", "private-value",
                                "hiddenReasoning", "hidden-value", "chain_of_thought", "hidden-chain")))), mapper);
        assertThat(result.toString()).contains("Public article", "c1", "[redacted]")
                .doesNotContain("not-for-output", "private-value", "hidden-value", "hidden-chain", "RETRIEVAL_PROVENANCE_MISSING");
    }

    @Test void emptyRunIsExplicitlyNotFound() {
        assertThat(RunDiagnostics.describe(run, List.of(), mapper).get("found")).isEqualTo(false);
    }
}
