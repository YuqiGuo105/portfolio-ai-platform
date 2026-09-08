package site.yuqi.knowledge.search;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import site.yuqi.knowledge.embedding.EmbeddingClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupabaseCurrentKnowledgeSearchTest {
    @Test
    void onlyCurrentEligibleEvidenceIsAccepted() {
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("status", "ACTIVE", "url", "/cv", "answer_visibility", "public"))).isTrue();
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("status", "SUPERSEDED", "url", "/cv"))).isFalse();
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("status", "ACTIVE", "url", "/cv", "retrieval_eligible", false))).isFalse();
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("type", "chat_qa"))).isFalse();
    }

    @Test
    void usesMatchingVectorDimensionsAndKeepsCanonicalSources() {
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.embedQuery("Git operations", 1536)).thenReturn(new float[1536]);
        var builder = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getPath()).isEqualTo("/rest/v1/rpc/match_current_knowledge_v2");
            assertThat(request.headers().getFirst("apikey")).isEqualTo("test-server-key");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("""
                      [{"id":"chunk-1","content":"Current article","similarity":0.9,
                        "metadata":{"status":"ACTIVE","answer_visibility":"public","source_type":"BLOG","source_id":"git","title":"Git Operations","url":"/blog-single/git","source_requires_login":true}},
                       {"id":"old","content":"Old answer","similarity":1.0,"metadata":{"status":"SUPERSEDED","url":"/cv"}}]
                      """).build());
        });
        var service = new SupabaseCurrentKnowledgeSearch(builder, embeddings, new KnowledgeSourceUrlResolver("https://www.yuqi.site"));
        ReflectionTestUtils.setField(service, "baseUrl", "https://test.supabase.co");
        ReflectionTestUtils.setField(service, "serviceKey", "test-server-key");
        ReflectionTestUtils.setField(service, "dimensions", 1536);
        var result = service.search("Git operations", 6);
        assertThat(result.results()).singleElement().satisfies(hit -> {
            assertThat(hit.sourceUrl()).isEqualTo("https://www.yuqi.site/blog-single/git");
            assertThat(hit.content()).isEqualTo("Current article");
            assertThat(hit.sourceRequiresLogin()).isTrue();
        });
        verify(embeddings).embedQuery("Git operations", 1536);
    }

    @Test
    void reviewedSamplesNeedExplicitApprovalAndPublicAnswerVisibilityNotInventedUrls() {
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("status", "ACTIVE", "source_type", "OWNER_QA",
                "evidence_review", "approved", "answer_visibility", "public"))).isTrue();
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("status", "ACTIVE", "source_type", "OWNER_QA",
                "evidence_review", "pending", "answer_visibility", "public"))).isFalse();
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("status", "ACTIVE", "url", "/cv",
                "answer_visibility", "private"))).isFalse();
        assertThat(SupabaseCurrentKnowledgeSearch.eligible(Map.of("status", "ACTIVE", "url", "/cv",
                "answer_visibility", "public", "retrieval_eligible", "false"))).isFalse();
    }
}
