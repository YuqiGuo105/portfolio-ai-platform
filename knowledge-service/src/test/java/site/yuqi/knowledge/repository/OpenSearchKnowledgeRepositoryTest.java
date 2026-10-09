package site.yuqi.knowledge.repository;

import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import site.yuqi.knowledge.config.OpenSearchProperties;
import site.yuqi.knowledge.model.KnowledgeChunk;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class OpenSearchKnowledgeRepositoryTest {
    @Test void keywordAndVectorQueriesEnforceRequestedVisibilityAndLocale() throws Exception {
        var client = mock(OpenSearchClient.class);
        var props = new OpenSearchProperties();
        var sent = new AtomicReference<SearchRequest>();
        var chunk = KnowledgeChunk.builder().chunkId("one").title("Title").build();
        var response = new SearchResponse.Builder<KnowledgeChunk>().took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hit -> hit.index("knowledge").id("one").source(chunk))).build();
        when(client.search(any(SearchRequest.class), eq(KnowledgeChunk.class))).thenAnswer(invocation -> {
            sent.set(invocation.getArgument(0));
            return response;
        });
        var repo = new OpenSearchKnowledgeRepository(client, props);
        assertThat(repo.keywordSearch("Kafka", List.of("public"), "en-US", 5)).containsExactly(chunk);
        assertThat(sent.get().size()).isEqualTo(5);
        var filters = sent.get().query().bool().filter();
        assertThat(filters.get(0).term().field()).isEqualTo("locale");
        assertThat(filters.get(1).terms().field()).isEqualTo("visibility");
        assertThat(filters.get(1).terms().terms().value().getFirst().stringValue()).isEqualTo("public");
        assertThat(repo.vectorSearch(new float[]{1, 0}, List.of("public"), "en-US", 3)).containsExactly(chunk);
        assertThat(sent.get().query().scriptScore().query().bool().filter()).hasSize(2);
        assertThat(sent.get().query().scriptScore().script().inline().lang()).isEqualTo("knn");
        repo.keywordSearch("all", List.of(), null, 1);
        assertThat(sent.get().query().bool().filter()).isEmpty();
        repo.vectorSearch(new float[]{1}, null, null, 1);
        assertThat(sent.get().query().scriptScore().query().bool().filter()).isEmpty();
    }

    @Test void contentProjectionMapsLegacyIdentifiersAndDeduplicatesBody() throws Exception {
        var client = mock(OpenSearchClient.class);
        var response = new SearchResponse.Builder<Map>().took(1).timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h.hits(hit -> hit.index("content").id("BLOG:42").source(Map.of(
                        "title", "Title", "summary", "Text", "body", "Text", "url", "/blog-single/42", "visibility", "private")))
                    .hits(hit -> hit.index("content").id("empty"))
                    .hits(hit -> hit.index("content").id("no-text").source(Map.of("type", "BLOG"))))
                .build();
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenAnswer(invocation -> {
            SearchRequest request = invocation.getArgument(0);
            assertThat(request.query().multiMatch().query()).isEqualTo("Kafka");
            return response;
        });
        var repo = new OpenSearchKnowledgeRepository(client, new OpenSearchProperties());
        var result = repo.contentProjectionSearch("Kafka", 5);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().sourceType()).isEqualTo("BLOG");
        assertThat(result.getFirst().sourceId()).isEqualTo("42");
        assertThat(result.getFirst().content()).isEqualTo("Text");
        assertThat(result.getFirst().visibility()).isEqualTo("private");
    }

    @Test void searchIoFailuresReturnNoFabricatedResults() throws Exception {
        var client = mock(OpenSearchClient.class);
        when(client.search(any(SearchRequest.class), eq(KnowledgeChunk.class))).thenThrow(new IOException("offline"));
        when(client.search(any(SearchRequest.class), eq(Map.class))).thenThrow(new IOException("offline"));
        var repo = new OpenSearchKnowledgeRepository(client, new OpenSearchProperties());
        assertThat(repo.keywordSearch("Kafka", List.of("public"), null, 2)).isEmpty();
        assertThat(repo.vectorSearch(new float[]{1}, List.of("public"), null, 2)).isEmpty();
        assertThat(repo.contentProjectionSearch("Kafka", 2)).isEmpty();
    }
}
