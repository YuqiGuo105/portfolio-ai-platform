package site.yuqi.mcp.idempotency;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache for write tools. The agent service is
 * required to supply a unique {@code Idempotency-Key} header per write
 * intent; replays of the same key within the TTL return the cached result
 * verbatim and do NOT re-invoke the downstream adapter.
 *
 * <p>Single-pod Cloud Run scoping is acceptable for Sprint 1. A Redis-backed
 * implementation can replace this without changing the interface.
 */
@Service
public class IdempotencyKeyService {

    @Value("${mcp.idempotency.ttl-seconds:3600}")
    private long ttlSeconds;

    private final ConcurrentHashMap<String, CachedResult> cache = new ConcurrentHashMap<>();

    public Optional<CachedResult> lookup(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        evict();
        CachedResult r = cache.get(key);
        if (r == null) return Optional.empty();
        if (Instant.now().isAfter(r.expiresAt)) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(r);
    }

    public void remember(String key, String toolName, Map<String, Object> result) {
        if (key == null || key.isBlank()) return;
        cache.put(key, new CachedResult(toolName, result, Instant.now().plusSeconds(ttlSeconds)));
        evict();
    }

    private void evict() {
        Instant now = Instant.now();
        cache.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt));
    }

    @Data
    @AllArgsConstructor
    public static class CachedResult {
        private String toolName;
        private Map<String, Object> result;
        private Instant expiresAt;
    }
}
