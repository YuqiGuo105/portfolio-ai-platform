package site.yuqi.agent.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.yuqi.agent.observability.EventRecorder;
import site.yuqi.ai.contracts.event.PlatformEvent;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Request-scoped state coordination for an agent run. Durable history is emitted
 * through EventRecorder; the local map only validates transitions while the run
 * is executing on this instance.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AgentRunLifecycle {

    private static final Map<AgentRunState, EnumSet<AgentRunState>> ALLOWED = allowedTransitions();

    private final EventRecorder eventRecorder;
    private final Map<UUID, AgentRunState> activeRuns = new ConcurrentHashMap<>();

    public void begin(UUID runId) {
        activeRuns.computeIfAbsent(runId, id -> {
            emit(id, null, AgentRunState.RECEIVED);
            return AgentRunState.RECEIVED;
        });
    }

    public void transition(UUID runId, AgentRunState next) {
        activeRuns.compute(runId, (id, current) -> {
            if (current == null) {
                throw new IllegalStateException("Unknown or finished agent run: " + runId);
            }
            if (current == next) {
                return current;
            }
            if (!ALLOWED.getOrDefault(current, EnumSet.noneOf(AgentRunState.class)).contains(next)) {
                throw new IllegalStateException("Invalid agent state transition: " + current + " -> " + next);
            }
            emit(runId, current, next);
            return next;
        });
    }

    public void complete(UUID runId, String finalStatus) {
        AgentRunState terminal = terminalState(finalStatus);
        // Serialize completion with transitions so terminal events cannot precede
        // an in-flight transition event. Repeated completion is a no-op.
        activeRuns.computeIfPresent(runId, (id, previous) -> {
            if (previous != terminal) emit(id, previous, terminal);
            return null;
        });
    }

    AgentRunState current(UUID runId) {
        return activeRuns.get(runId);
    }

    private void emit(UUID runId, AgentRunState previous, AgentRunState current) {
        try {
            eventRecorder.record(PlatformEvent.now("agent_run.state_changed")
                .runId(runId)
                .service("agent-runtime-service")
                .status(current.name().toLowerCase())
                .payload(Map.of(
                        "previousState", previous == null ? "" : previous.name(),
                        "state", current.name()))
                    .build());
        } catch (RuntimeException e) {
            // Observability failure must not change workflow semantics or leak active runs.
            log.warn("Failed to record state for run={} state={}", runId, current, e);
        }
    }

    private static AgentRunState terminalState(String status) {
        if ("blocked".equals(status) || "forbidden".equals(status)) return AgentRunState.BLOCKED;
        if (status != null && status.startsWith("handoff")) return AgentRunState.HANDOFF;
        if ("failed".equals(status) || "error".equals(status) || "budget_exhausted".equals(status)) return AgentRunState.FAILED;
        return AgentRunState.COMPLETED;
    }

    private static Map<AgentRunState, EnumSet<AgentRunState>> allowedTransitions() {
        Map<AgentRunState, EnumSet<AgentRunState>> transitions = new EnumMap<>(AgentRunState.class);
        transitions.put(AgentRunState.RECEIVED, EnumSet.of(AgentRunState.ADMITTED));
        transitions.put(AgentRunState.ADMITTED, EnumSet.of(
                AgentRunState.GUARDING, AgentRunState.WAITING_CONFIRMATION, AgentRunState.HANDOFF));
        transitions.put(AgentRunState.GUARDING, EnumSet.of(AgentRunState.PLANNING, AgentRunState.BLOCKED));
        transitions.put(AgentRunState.PLANNING, EnumSet.of(
                AgentRunState.WAITING_CONFIRMATION, AgentRunState.RETRIEVING,
                AgentRunState.EXECUTING_TOOL, AgentRunState.GENERATING,
                AgentRunState.FINALIZING, AgentRunState.HANDOFF));
        transitions.put(AgentRunState.WAITING_CONFIRMATION, EnumSet.of(
                AgentRunState.EXECUTING_TOOL, AgentRunState.FINALIZING));
        transitions.put(AgentRunState.RETRIEVING, EnumSet.of(AgentRunState.GENERATING, AgentRunState.FINALIZING));
        transitions.put(AgentRunState.EXECUTING_TOOL, EnumSet.of(AgentRunState.FINALIZING));
        transitions.put(AgentRunState.GENERATING, EnumSet.of(AgentRunState.FINALIZING));
        transitions.put(AgentRunState.FINALIZING, EnumSet.of(AgentRunState.COMPLETED));
        return Map.copyOf(transitions);
    }
}
