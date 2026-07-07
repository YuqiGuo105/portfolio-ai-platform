package site.yuqi.agent.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnBean(JdbcTemplate.class)
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    private static final int MAX_ATTEMPTS = 10;

    public void insert(String eventType, String payloadJson) {
        jdbc.update(
                "insert into outbox_event (id, event_type, payload_json, status, created_at) values (?, ?, ?::jsonb, 'pending', now())",
                UUID.randomUUID(), eventType, payloadJson);
    }

    public List<OutboxEvent> findPendingBatch(int limit) {
        return jdbc.query(
                """
                select id, event_type, payload_json, attempts, created_at from outbox_event
                where status = 'pending'
                   or (status = 'failed' and attempts < ? and (next_retry_at is null or next_retry_at <= now()))
                order by created_at limit ?
                """,
                (rs, i) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload_json"),
                        rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant()),
                MAX_ATTEMPTS, limit);
    }

    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) return;
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        jdbc.update(
                "update outbox_event set status = 'published', published_at = now() where id in (" + placeholders + ")",
                ids.toArray());
    }

    public void markFailedWithRetry(List<UUID> ids) {
        if (ids.isEmpty()) return;
        for (UUID id : ids) {
            jdbc.update("""
                update outbox_event
                set status = case when attempts + 1 >= ? then 'dead_letter' else 'failed' end,
                    attempts = attempts + 1,
                    next_retry_at = now() + (power(2, least(attempts + 1, 8)) || ' seconds')::interval
                where id = ?
                """, MAX_ATTEMPTS, id);
        }
    }

    public int countDeadLetters() {
        Integer count = jdbc.queryForObject(
                "select count(*) from outbox_event where status = 'dead_letter'", Integer.class);
        return count != null ? count : 0;
    }

    public int countPending() {
        Integer count = jdbc.queryForObject(
                "select count(*) from outbox_event where status in ('pending', 'failed')", Integer.class);
        return count != null ? count : 0;
    }

    public record OutboxEvent(UUID id, String eventType, String payloadJson, int attempts, Instant createdAt) {}
}
