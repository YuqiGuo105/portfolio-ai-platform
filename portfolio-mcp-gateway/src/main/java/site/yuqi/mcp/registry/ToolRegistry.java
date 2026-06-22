package site.yuqi.mcp.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import site.yuqi.mcp.model.ToolCatalog;
import site.yuqi.mcp.model.ToolDefinition;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads {@code tool-catalog.yaml} at startup and exposes lookup by tool
 * name. The catalog is the single source of truth for what the gateway can
 * dispatch — if a tool is not here, every invoke is rejected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final ResourceLoader resourceLoader;

    @Value("${mcp.catalog.location:classpath:tool-catalog.yaml}")
    private String catalogLocation;

    private final Map<String, ToolDefinition> byName = new LinkedHashMap<>();

    @PostConstruct
    void load() throws Exception {
        Resource res = resourceLoader.getResource(catalogLocation);
        if (!res.exists()) {
            throw new IllegalStateException("Tool catalog not found at " + catalogLocation);
        }

        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        try (InputStream in = res.getInputStream()) {
            ToolCatalog catalog = yaml.readValue(in, ToolCatalog.class);
            if (catalog.getTools() == null || catalog.getTools().isEmpty()) {
                throw new IllegalStateException("Tool catalog is empty.");
            }
            for (ToolDefinition def : catalog.getTools()) {
                if (def.getName() == null || def.getName().isBlank()) {
                    throw new IllegalStateException("Tool definition missing name: " + def);
                }
                if (byName.put(def.getName(), def) != null) {
                    throw new IllegalStateException("Duplicate tool definition: " + def.getName());
                }
            }
        }
        log.info("ToolRegistry loaded {} tools from {}", byName.size(), catalogLocation);
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<ToolDefinition> all() {
        return List.copyOf(byName.values());
    }
}
