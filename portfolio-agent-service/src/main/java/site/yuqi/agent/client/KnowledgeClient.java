package site.yuqi.agent.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchRequest;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;

import java.time.Duration;

/**
 * Client for the Knowledge Retrieval Service (hybrid RAG).
 * Calls POST /internal/v1/knowledge/search on knowledge-service.
 */
@Slf4j
@Component
public class KnowledgeClient {

    private final WebClient webClient;

    public KnowledgeClient(WebClient.Builder builder,
                           @Value("${knowledge.base-url:http://localhost:8092}") String baseUrl,
                           @Value("${knowledge.timeout-ms:10000}") int timeoutMs) {
        this.webClient = builder.baseUrl(baseUrl)
                .build();
    }

    public KnowledgeSearchResponse search(String query, int topK) {
        KnowledgeSearchRequest request = KnowledgeSearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

        try {
            return webClient.post()
                    .uri("/internal/v1/knowledge/search")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(KnowledgeSearchResponse.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();
        } catch (Exception e) {
            log.error("Knowledge search failed: {}", e.getMessage());
            return null;
        }
    }
}
