package site.yuqi.knowledge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Knowledge chunk — document stored in the OpenSearch knowledge-chunks-v1 index.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeChunk(
        @JsonProperty("chunk_id") String chunkId,
        @JsonProperty("document_id") String documentId,
        @JsonProperty("source_type") String sourceType,
        @JsonProperty("source_id") String sourceId,
        @JsonProperty("source_url") String sourceUrl,
        String title,
        String content,
        String locale,
        String visibility,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("embedding_model") String embeddingModel,
        float[] embedding
) {}
