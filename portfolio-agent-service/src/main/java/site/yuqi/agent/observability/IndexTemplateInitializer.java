package site.yuqi.agent.observability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.indices.PutIndexTemplateRequest;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * On startup, ensures an index template for ai-* indexes that sets
 * 1 primary shard and 0 replicas — critical for free-tier shard budgets.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexTemplateInitializer {

    private final OpenSearchClient openSearchClient;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureTemplate() {
        try {
            openSearchClient.indices().putIndexTemplate(PutIndexTemplateRequest.of(t -> t
                    .name("ai-observability-free-tier")
                    .indexPatterns("ai-*")
                    .template(tmpl -> tmpl
                            .settings(s -> s
                                    .numberOfShards("1")
                                    .numberOfReplicas("0")
                            )
                    )
                    .priority(100)
            ));
            log.info("Index template 'ai-observability-free-tier' applied (1 shard, 0 replicas)");
        } catch (Exception e) {
            log.warn("Failed to apply index template: {}", e.getMessage());
        }
    }
}
