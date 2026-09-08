package site.yuqi.knowledge.search;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;
import site.yuqi.knowledge.embedding.EmbeddingClient;
import site.yuqi.knowledge.model.KnowledgeChunk;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Current, versioned Supabase evidence. Never mixes legacy OpenAI vectors with Gemini vectors. */
@Component
@RequiredArgsConstructor
public class SupabaseCurrentKnowledgeSearch {
    private final WebClient.Builder builder;
    private final EmbeddingClient embeddings;
    private final KnowledgeSourceUrlResolver urls;

    @Value("${knowledge.supabase.enabled:false}") private boolean enabled;
    @Value("${knowledge.supabase.url:}") private String baseUrl;
    @Value("${knowledge.supabase.service-key:}") private String serviceKey;
    @Value("${knowledge.supabase.dimensions:1536}") private int dimensions;
    @Value("${knowledge.supabase.min-similarity:0.35}") private double minSimilarity;

    public boolean isEnabled() { return enabled; }

    public KnowledgeSearchResponse search(String query, int topK) {
        if (baseUrl == null || baseUrl.isBlank() || serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("Current knowledge store is not configured");
        }
        long start = System.currentTimeMillis();
        float[] vector = embeddings.embedQuery(query, dimensions);
        if (vector.length != dimensions) throw new IllegalStateException("Invalid knowledge query vector dimensions");
        List<Row> rows = builder.clone().baseUrl(baseUrl).build().post()
                .uri("/rest/v1/rpc/match_current_knowledge_v2")
                .header("apikey", serviceKey).header("Authorization", "Bearer " + serviceKey)
                .bodyValue(Map.of("query_embedding", vector, "match_count", Math.max(1, Math.min(topK, 20)),
                        "min_similarity", minSimilarity))
                .retrieve().bodyToFlux(Row.class).collectList().timeout(Duration.ofSeconds(3)).block();
        return KnowledgeSearchResponse.builder().queryId(UUID.randomUUID().toString())
                .retrievalStrategy("supabase_current_gemini_vector")
                .latencyMs((int) (System.currentTimeMillis() - start))
                .results(rows == null ? List.of() : rows.stream().filter(row -> eligible(row.metadata()))
                        .map(row -> {
                            Map<String, Object> meta = row.metadata();
                            KnowledgeChunk chunk = KnowledgeChunk.builder().sourceUrl(text(meta, "url"))
                                    .sourceId(text(meta, "source_id")).sourceType(text(meta, "source_type")).build();
                            return KnowledgeSearchResponse.ChunkHit.builder().chunkId(row.id())
                                    .documentId(text(meta, "source_id")).sourceId(text(meta, "source_id"))
                                    .sourceType(text(meta, "source_type")).title(text(meta, "title"))
                                    .sourceUrl(urls.resolve(chunk))
                                    .sourceRequiresLogin(Boolean.TRUE.equals(meta.get("source_requires_login")))
                                    .content(row.content()).score(row.similarity()).build();
                        }).toList()).build();
    }

    static boolean eligible(Map<String, Object> meta) {
        return meta != null && "ACTIVE".equals(meta.get("status"))
                && !"false".equals(String.valueOf(meta.get("retrieval_eligible")))
                && "public".equals(text(meta, "answer_visibility"))
                && (isReviewedAnswer(meta) || !text(meta, "url").isBlank());
    }

    private static boolean isReviewedAnswer(Map<String, Object> meta) {
        return List.of("OWNER_QA", "PROFILE").contains(text(meta, "source_type"))
                && "approved".equals(text(meta, "evidence_review"));
    }

    static boolean approvedAnswer(Map<String, Object> meta) {
        return eligible(meta) && isReviewedAnswer(meta)
                && Boolean.TRUE.equals(meta.get("retrieval_eligible"));
    }

    private static String text(Map<String, Object> meta, String key) {
        return meta.get(key) instanceof String value ? value : "";
    }

    record Row(String id, String content, Map<String, Object> metadata, double similarity) {}
}
