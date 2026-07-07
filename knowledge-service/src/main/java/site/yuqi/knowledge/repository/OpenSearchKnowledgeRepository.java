package site.yuqi.knowledge.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.*;
import org.opensearch.client.opensearch.core.search.Hit;
import org.springframework.stereotype.Repository;
import site.yuqi.knowledge.config.OpenSearchProperties;
import site.yuqi.knowledge.model.KnowledgeChunk;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * OpenSearch knowledge repository — upserts chunks and executes BM25 / kNN queries.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OpenSearchKnowledgeRepository {

    private final OpenSearchClient client;
    private final OpenSearchProperties props;

    public void upsertChunk(KnowledgeChunk chunk) {
        try {
            client.index(i -> i
                    .index(props.getKnowledgeIndex())
                    .id(chunk.chunkId())
                    .document(chunk));
        } catch (IOException e) {
            throw new RuntimeException("Failed to upsert chunk " + chunk.chunkId(), e);
        }
    }

    /**
     * BM25 keyword search。
     */
    public List<KnowledgeChunk> keywordSearch(String query, List<String> visibility, String locale, int topK) {
        try {
            var response = client.search(s -> s
                            .index(props.getKnowledgeIndex())
                            .size(topK)
                            .query(q -> q.bool(b -> {
                                b.must(m -> m.multiMatch(mm -> mm
                                        .query(query)
                                        .fields(List.of("title^2", "content"))));
                                if (locale != null) {
                                    b.filter(f -> f.term(t -> t.field("locale")
                                            .value(org.opensearch.client.opensearch._types.FieldValue.of(locale))));
                                }
                                if (visibility != null && !visibility.isEmpty()) {
                                    b.filter(f -> f.terms(t -> t
                                            .field("visibility")
                                            .terms(tv -> tv.value(visibility.stream()
                                                    .map(v -> org.opensearch.client.opensearch._types.FieldValue.of(v))
                                                    .toList()))));
                                }
                                return b;
                            })),
                    KnowledgeChunk.class);

            return response.hits().hits().stream()
                    .map(Hit::source)
                    .toList();
        } catch (IOException e) {
            log.error("BM25 search failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * kNN vector search。
     */
    public List<KnowledgeChunk> vectorSearch(float[] embedding, List<String> visibility, String locale, int topK) {
        try {
            // 使用 script_score 做 kNN（兼容 OpenSearch free tier 无 HNSW plugin）
            var response = client.search(s -> s
                            .index(props.getKnowledgeIndex())
                            .size(topK)
                            .query(q -> q.scriptScore(ss -> ss
                                    .query(inner -> inner.bool(b -> {
                                        b.must(m -> m.matchAll(ma -> ma));
                                        if (locale != null) {
                                            b.filter(f -> f.term(t -> t.field("locale")
                                                    .value(org.opensearch.client.opensearch._types.FieldValue.of(locale))));
                                        }
                                        if (visibility != null && !visibility.isEmpty()) {
                                            b.filter(f -> f.terms(t -> t
                                                    .field("visibility")
                                                    .terms(tv -> tv.value(visibility.stream()
                                                            .map(v -> org.opensearch.client.opensearch._types.FieldValue.of(v))
                                                            .toList()))));
                                        }
                                        return b;
                                    }))
                                    .script(script -> script.inline(inline -> inline
                                            .source("knn_score")
                                            .lang("knn")
                                            .params(Map.of(
                                                    "field", org.opensearch.client.json.JsonData.of("embedding"),
                                                    "query_value", org.opensearch.client.json.JsonData.of(embedding),
                                                    "space_type", org.opensearch.client.json.JsonData.of("cosinesimil")
                                            )))))),
                    KnowledgeChunk.class);

            return response.hits().hits().stream()
                    .map(Hit::source)
                    .toList();
        } catch (IOException e) {
            log.error("Vector search failed: {}", e.getMessage());
            return List.of();
        }
    }
}
