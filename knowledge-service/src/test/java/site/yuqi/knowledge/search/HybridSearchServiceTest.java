package site.yuqi.knowledge.search;

import org.junit.jupiter.api.Test;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchRequest;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;
import site.yuqi.knowledge.embedding.EmbeddingClient;
import site.yuqi.knowledge.model.KnowledgeChunk;
import site.yuqi.knowledge.repository.OpenSearchKnowledgeRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridSearchServiceTest {

    @Test
    void fallsBackToContentProjectionWhenRagIndexesHaveNoHits() {
        OpenSearchKnowledgeRepository repository = mock(OpenSearchKnowledgeRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        KnowledgeSourceUrlResolver urlResolver =
                new KnowledgeSourceUrlResolver("https://www.yuqi.site");
        HybridSearchService service =
                new HybridSearchService(repository, embeddingClient, urlResolver);

        when(repository.keywordSearch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(embeddingClient.embed(anyString())).thenThrow(new IllegalStateException("provider down"));
        when(repository.contentProjectionSearch("Portfolio Platform", 6))
                .thenReturn(List.of(KnowledgeChunk.builder()
                        .chunkId("content:PROJECT:project-1")
                        .documentId("PROJECT:project-1")
                        .sourceType("PROJECT")
                        .sourceId("project-1")
                        .sourceUrl("/work-single/project-1")
                        .title("Portfolio Platform")
                        .content("Production-minded distributed platform.")
                        .build()));

        KnowledgeSearchResponse response = service.search(KnowledgeSearchRequest.builder()
                .query("Portfolio Platform")
                .topK(6)
                .build());

        assertThat(response.results()).singleElement().satisfies(hit -> {
            assertThat(hit.title()).isEqualTo("Portfolio Platform");
            assertThat(hit.sourceUrl())
                    .isEqualTo("https://www.yuqi.site/work-single/project-1");
        });
    }

    @Test
    void doesNotQueryContentProjectionWhenRagReturnsEvidence() {
        OpenSearchKnowledgeRepository repository = mock(OpenSearchKnowledgeRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        KnowledgeSourceUrlResolver urlResolver =
                new KnowledgeSourceUrlResolver("https://www.yuqi.site");
        HybridSearchService service =
                new HybridSearchService(repository, embeddingClient, urlResolver);
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .chunkId("chunk-1")
                .documentId("project-1")
                .sourceType("PROJECT")
                .sourceId("project-1")
                .title("Portfolio Platform")
                .content("RAG evidence")
                .build();

        when(repository.keywordSearch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(chunk));
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f});
        when(repository.vectorSearch(any(float[].class), any(), anyString(), anyInt()))
                .thenReturn(List.of());

        KnowledgeSearchResponse response = service.search(KnowledgeSearchRequest.builder()
                .query("Portfolio Platform")
                .topK(6)
                .build());

        assertThat(response.results()).hasSize(1);
        verify(repository, never()).contentProjectionSearch(anyString(), anyInt());
    }

    @Test
    void fallsBackWhenLegacyRagEvidenceHasNoCanonicalLink() {
        OpenSearchKnowledgeRepository repository = mock(OpenSearchKnowledgeRepository.class);
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        KnowledgeSourceUrlResolver urlResolver =
                new KnowledgeSourceUrlResolver("https://www.yuqi.site");
        HybridSearchService service =
                new HybridSearchService(repository, embeddingClient, urlResolver);
        KnowledgeChunk legacyChunk = KnowledgeChunk.builder()
                .chunkId("legacy-portfolio-0")
                .documentId("legacy-portfolio")
                .sourceType("portfolio")
                .title("Portfolio overview")
                .content("Generic legacy portfolio context.")
                .build();
        KnowledgeChunk projected = KnowledgeChunk.builder()
                .chunkId("content:PROJECT:project-1")
                .documentId("PROJECT:project-1")
                .sourceType("PROJECT")
                .sourceId("project-1")
                .sourceUrl("/work-single/project-1")
                .title("Portfolio Platform")
                .content("Production-minded distributed platform.")
                .build();

        when(repository.keywordSearch(anyString(), any(), anyString(), anyInt()))
                .thenReturn(List.of(legacyChunk));
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f});
        when(repository.vectorSearch(any(float[].class), any(), anyString(), anyInt()))
                .thenReturn(List.of());
        when(repository.contentProjectionSearch("Portfolio Platform", 6))
                .thenReturn(List.of(projected));

        KnowledgeSearchResponse response = service.search(KnowledgeSearchRequest.builder()
                .query("Portfolio Platform")
                .topK(6)
                .build());

        assertThat(response.results()).singleElement().satisfies(hit -> {
            assertThat(hit.title()).isEqualTo("Portfolio Platform");
            assertThat(hit.sourceUrl())
                    .isEqualTo("https://www.yuqi.site/work-single/project-1");
        });
    }
}
