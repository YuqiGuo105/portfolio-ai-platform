package site.yuqi.knowledge.ingestion;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import site.yuqi.knowledge.embedding.EmbeddingClient;
import site.yuqi.knowledge.model.KnowledgeChunk;
import site.yuqi.knowledge.repository.OpenSearchKnowledgeRepository;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IngestionServiceTest {
    @Test void chunksWithOverlapAndPreservesSourceAndAccessMetadata() {
        var embeddings = mock(EmbeddingClient.class);
        var repository = mock(OpenSearchKnowledgeRepository.class);
        when(embeddings.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        var service = new IngestionService(embeddings, repository);
        String content = "0123456789".repeat(310);
        var request = new IngestionService.IngestRequest("doc-1", "BLOG", "source-1", "/blog-single/source-1", "Title", content, "zh", "private");
        assertThat(service.ingest(request)).isEqualTo(3);
        var captor = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(repository, times(3)).upsertChunk(captor.capture());
        List<KnowledgeChunk> chunks = captor.getAllValues();
        assertThat(chunks.get(0).content()).isEqualTo(content.substring(0, 1500));
        assertThat(chunks.get(1).content()).isEqualTo(content.substring(1300, 2800));
        assertThat(chunks.get(2).content()).isEqualTo(content.substring(2600));
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.visibility()).isEqualTo("private");
            assertThat(chunk.locale()).isEqualTo("zh");
            assertThat(chunk.sourceUrl()).isEqualTo("/blog-single/source-1");
            assertThat(chunk.embedding()).containsExactly(0.1f, 0.2f);
        });
        assertThat(chunks.get(2).chunkId()).isEqualTo("doc-1-2");
    }

    @Test void emptyInputDoesNotCallProvidersAndDefaultsRemainStable() {
        var embeddings = mock(EmbeddingClient.class);
        var repository = mock(OpenSearchKnowledgeRepository.class);
        var service = new IngestionService(embeddings, repository);
        assertThat(service.ingest(new IngestionService.IngestRequest("empty", "BLOG", "id", "/", "Empty", "", null, null))).isZero();
        verifyNoInteractions(embeddings, repository);
        when(embeddings.embed("text")).thenReturn(new float[]{1});
        service.ingest(new IngestionService.IngestRequest("short", "BLOG", "id", "/", "Short", "text", null, null));
        var chunk = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(repository).upsertChunk(chunk.capture());
        assertThat(chunk.getValue().locale()).isEqualTo("en-US");
        assertThat(chunk.getValue().visibility()).isEqualTo("public");
    }

    @Test void providerFailureDoesNotReportSuccessfulIndexing() {
        var embeddings = mock(EmbeddingClient.class);
        var repository = mock(OpenSearchKnowledgeRepository.class);
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        var service = new IngestionService(embeddings, repository);
        assertThatThrownBy(() -> service.ingest(new IngestionService.IngestRequest("doc", "BLOG", "id", "/", "Title", "text", null, null))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }
}
