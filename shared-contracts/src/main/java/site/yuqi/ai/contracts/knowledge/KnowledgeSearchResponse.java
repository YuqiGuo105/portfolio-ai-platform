package site.yuqi.ai.contracts.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeSearchResponse(
        String queryId,
        List<ChunkHit> results,
        int latencyMs,
        String retrievalStrategy
) {
    public KnowledgeSearchResponse(String queryId, List<ChunkHit> results, int latencyMs) {
        this(queryId, results, latencyMs, null);
    }
    @Builder
    public record ChunkHit(
            String chunkId,
            String documentId,
            String title,
            String content,
            double score,
            String sourceType,
            String sourceId,
            String sourceUrl,
            boolean sourceRequiresLogin
    ) {
        public ChunkHit(String chunkId, String documentId, String title, String content, double score,
                        String sourceType, String sourceId, String sourceUrl) {
            this(chunkId, documentId, title, content, score, sourceType, sourceId, sourceUrl, false);
        }
    }
}
