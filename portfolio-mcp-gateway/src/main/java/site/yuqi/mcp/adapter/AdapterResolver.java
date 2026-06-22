package site.yuqi.mcp.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Indexes adapters by {@code target()} so the gateway controller can pick
 * the right one without a switch statement.
 */
@Slf4j
@Component
public class AdapterResolver {

    private final Map<String, DomainServiceAdapter> byTarget;

    public AdapterResolver(List<DomainServiceAdapter> adapters) {
        Map<String, DomainServiceAdapter> map = new HashMap<>();
        for (DomainServiceAdapter a : adapters) {
            if (map.put(a.target(), a) != null) {
                throw new IllegalStateException("Duplicate adapter target: " + a.target());
            }
        }
        this.byTarget = Map.copyOf(map);
        log.info("AdapterResolver wired with targets: {}", byTarget.keySet());
    }

    public Optional<DomainServiceAdapter> find(String target) {
        return Optional.ofNullable(byTarget.get(target));
    }
}
