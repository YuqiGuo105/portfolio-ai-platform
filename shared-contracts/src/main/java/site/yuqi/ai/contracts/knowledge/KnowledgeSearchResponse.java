package site.yuqi.ai.contracts.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeSearchResponse(
        String queryId,
        List<ChunkHit> results,
        int latencyMs
) {
    @Builder
    public record ChunkHit(
            String chunkId,
            String documentId,
            String title,
            String content,
            double score,
            String sourceType,
            String sourceId,
            String sourceUrl
    ) {}
}
