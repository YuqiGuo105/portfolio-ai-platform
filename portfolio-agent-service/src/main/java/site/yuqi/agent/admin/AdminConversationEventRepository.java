package site.yuqi.agent.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AdminConversationEventRepository {

    private final JdbcTemplate jdbc;

    public List<EventRow> findRunEvents(Instant since, String query, int runLimit) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String pattern = "%" + normalized + "%";

        return jdbc.query("""
                with candidate_events as (
                    select id, event_type, payload_json, created_at,
                           payload_json ->> 'runId' as run_id
                    from outbox_event
                    where created_at >= ?
                      and event_type in (
                          'agent_run', 'agent_step', 'answer', 'model_call',
                          'retrieval', 'safety', 'tool_call'
                      )
                      and coalesce(payload_json ->> 'runId', '') <> ''
                ), matching_runs as (
                    select run_id, max(created_at) as last_event_at
                    from candidate_events
                    where ? = '' or lower(payload_json::text) like ?
                    group by run_id
                    order by last_event_at desc
                    limit ?
                )
                select event.id, event.event_type, event.payload_json, event.created_at
                from candidate_events event
                join matching_runs run on run.run_id = event.run_id
                order by run.last_event_at desc, event.created_at asc
                """,
                (rs, rowNum) -> new EventRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload_json"),
                        rs.getTimestamp("created_at").toInstant()),
                Timestamp.from(since), normalized, pattern, runLimit);
    }

    public record EventRow(UUID id, String category, String payloadJson, Instant createdAt) {
    }
}
