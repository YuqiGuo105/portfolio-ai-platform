package site.yuqi.knowledge.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchRequest;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse.ChunkHit;
import site.yuqi.knowledge.embedding.EmbeddingClient;
import site.yuqi.knowledge.model.KnowledgeChunk;
import site.yuqi.knowledge.repository.OpenSearchKnowledgeRepository;

import java.util.*;
import java.util.stream.IntStream;

/**
 * Hybrid search service — merges BM25 keyword results and kNN vector results
 * using Reciprocal Rank Fusion (RRF).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridSearchService {

    private final OpenSearchKnowledgeRepository repository;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeSourceUrlResolver sourceUrlResolver;

    @Value("${knowledge.search.default-top-k:8}")
    private int defaultTopK;

    @Value("${knowledge.search.rrf-k:60}")
    private int rrfK;

    public KnowledgeSearchResponse search(KnowledgeSearchRequest request) {
        long start = System.currentTimeMillis();
        int topK = request.topK() > 0 ? request.topK() : defaultTopK;

        // 1. BM25 keyword search
        List<KnowledgeChunk> bm25Results = repository.keywordSearch(
                request.query(), request.visibility(), request.locale(), topK * 2);

        // 2. kNN vector search. BM25 and the content projection remain
        // available if the external embedding provider is temporarily down.
        List<KnowledgeChunk> vectorResults;
        try {
            float[] queryEmbedding = embeddingClient.embed(request.query());
            vectorResults = repository.vectorSearch(
                    queryEmbedding, request.visibility(), request.locale(), topK * 2);
        } catch (RuntimeException e) {
            log.warn("Vector retrieval unavailable; continuing with BM25: {}", e.getMessage());
            vectorResults = List.of();
        }

        // 3. RRF merge
        List<KnowledgeChunk> merged = reciprocalRankFusion(bm25Results, vectorResults, topK);
        boolean contentProjectionFallback = merged.isEmpty()
                || merged.stream().noneMatch(chunk -> sourceUrlResolver.resolve(chunk) != null);
        if (contentProjectionFallback) {
            List<KnowledgeChunk> projected = repository.contentProjectionSearch(request.query(), topK);
            if (!projected.isEmpty()) {
                merged = projected;
            }
        }

        // 4. Build response
        List<ChunkHit> hits = merged.stream()
                .map(c -> ChunkHit.builder()
                        .chunkId(c.chunkId())
                        .documentId(c.documentId())
                        .title(c.title())
                        .content(c.content())
                        .sourceType(c.sourceType())
                        .sourceId(c.sourceId())
                        .sourceUrl(sourceUrlResolver.resolve(c))
                        .score(0.0) // RRF score not directly comparable
                        .build())
                .toList();

        int latencyMs = (int) (System.currentTimeMillis() - start);
        log.info("Hybrid search completed: bm25={} vector={} contentFallback={} merged={} latency={}ms",
                bm25Results.size(), vectorResults.size(), contentProjectionFallback, hits.size(), latencyMs);

        return KnowledgeSearchResponse.builder()
                .queryId(UUID.randomUUID().toString())
                .results(hits)
                .latencyMs(latencyMs)
                .build();
    }

    /**
     * Reciprocal Rank Fusion: score(d) = sum( 1 / (k + rank_i(d)) ) for each list.
     */
    private List<KnowledgeChunk> reciprocalRankFusion(
            List<KnowledgeChunk> list1, List<KnowledgeChunk> list2, int topK) {

        Map<String, Double> scores = new HashMap<>();
        Map<String, KnowledgeChunk> chunkMap = new HashMap<>();

        // Score from list1
        for (int i = 0; i < list1.size(); i++) {
            KnowledgeChunk c = list1.get(i);
            scores.merge(c.chunkId(), 1.0 / (rrfK + i + 1), Double::sum);
            chunkMap.putIfAbsent(c.chunkId(), c);
        }

        // Score from list2
        for (int i = 0; i < list2.size(); i++) {
            KnowledgeChunk c = list2.get(i);
            scores.merge(c.chunkId(), 1.0 / (rrfK + i + 1), Double::sum);
            chunkMap.putIfAbsent(c.chunkId(), c);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> chunkMap.get(e.getKey()))
                .toList();
    }
}
