package site.yuqi.knowledge.ingestion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import site.yuqi.knowledge.embedding.EmbeddingClient;
import site.yuqi.knowledge.model.KnowledgeChunk;
import site.yuqi.knowledge.repository.OpenSearchKnowledgeRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Document ingestion — splits text into chunks, generates embeddings, upserts to OpenSearch.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private static final int MAX_CHUNK_CHARS = 1500;
    private static final int OVERLAP_CHARS = 200;

    private final EmbeddingClient embeddingClient;
    private final OpenSearchKnowledgeRepository repository;

    public record IngestRequest(
            String documentId,
            String sourceType,
            String sourceId,
            String sourceUrl,
            String title,
            String content,
            String locale,
            String visibility
    ) {}

    public int ingest(IngestRequest req) {
        List<String> chunks = chunkText(req.content());
        int indexed = 0;

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            float[] embedding = embeddingClient.embed(chunkText);

            KnowledgeChunk chunk = KnowledgeChunk.builder()
                    .chunkId(req.documentId() + "-" + i)
                    .documentId(req.documentId())
                    .sourceType(req.sourceType())
                    .sourceId(req.sourceId())
                    .sourceUrl(req.sourceUrl())
                    .title(req.title())
                    .content(chunkText)
                    .locale(req.locale() != null ? req.locale() : "en-US")
                    .visibility(req.visibility() != null ? req.visibility() : "public")
                    .updatedAt(Instant.now().toString())
                    .embeddingModel("text-embedding-004")
                    .embedding(embedding)
                    .build();

            repository.upsertChunk(chunk);
            indexed++;
        }

        log.info("Ingested document={} chunks={}", req.documentId(), indexed);
        return indexed;
    }

    /**
     * Simple sliding-window chunker with overlap.
     */
    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHUNK_CHARS, text.length());
            chunks.add(text.substring(start, end));
            start += MAX_CHUNK_CHARS - OVERLAP_CHARS;
        }
        return chunks;
    }
}
