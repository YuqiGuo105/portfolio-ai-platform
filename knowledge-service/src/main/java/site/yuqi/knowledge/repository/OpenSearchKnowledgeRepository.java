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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * Reads the content-search projection when the chunk index has no result.
     *
     * <p>The projection has existed in both snake_case and camelCase shapes;
     * the mapper accepts both so an alias migration does not break chat links.
     */
    public List<KnowledgeChunk> contentProjectionSearch(String query, int topK) {
        try {
            var response = client.search(s -> s
                            .index(props.getContentIndex())
                            .size(topK)
                            .query(q -> q.multiMatch(mm -> mm
                                    .query(query)
                                    .fields(List.of(
                                            "title^4",
                                            "summary^2",
                                            "content",
                                            "body",
                                            "tags^2",
                                            "category",
                                            "search_terms^1.5")))),
                    Map.class);

            List<KnowledgeChunk> results = new ArrayList<>();
            for (Hit<Map> hit : response.hits().hits()) {
                Map<String, Object> source = hit.source();
                if (source == null) continue;
                String documentId = firstText(source, "id", "document_id");
                if (documentId == null) documentId = hit.id();
                String sourceType = firstText(source, "source_type", "type");
                String sourceId = firstText(source, "source_id");
                if (sourceId == null && documentId != null && documentId.contains(":")) {
                    sourceId = documentId.substring(documentId.indexOf(':') + 1);
                }
                if (sourceType == null && documentId != null && documentId.contains(":")) {
                    sourceType = documentId.substring(0, documentId.indexOf(':'));
                }
                String title = firstText(source, "title");
                String content = joinContent(
                        firstText(source, "summary"),
                        firstText(source, "content", "body"));
                if (title == null && content == null) continue;

                results.add(KnowledgeChunk.builder()
                        .chunkId("content:" + documentId)
                        .documentId(documentId)
                        .sourceType(sourceType)
                        .sourceId(sourceId)
                        .sourceUrl(firstText(source, "url"))
                        .title(title)
                        .content(content)
                        .visibility(firstText(source, "visibility"))
                        .build());
            }
            return List.copyOf(results);
        } catch (IOException | RuntimeException e) {
            log.warn("Content projection search failed: {}", e.getMessage());
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

    private static String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    private static String joinContent(String summary, String body) {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        if (summary != null && !summary.isBlank()) unique.put(summary.trim(), summary.trim());
        if (body != null && !body.isBlank()) unique.put(body.trim(), body.trim());
        return unique.isEmpty() ? null : String.join("\n\n", unique.values());
    }
}
