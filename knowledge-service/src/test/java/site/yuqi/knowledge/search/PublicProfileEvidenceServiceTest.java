package site.yuqi.knowledge.search;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicProfileEvidenceServiceTest {
    private Map<String, Object> approved() {
        return new HashMap<>(Map.of("status", "ACTIVE", "answer_visibility", "public",
                "evidence_review", "approved", "source_type", "PROFILE", "retrieval_eligible", true));
    }

    @Test void requiresExplicitPublicApprovalAndRetrievalEligibility() {
        assertThat(SupabaseCurrentKnowledgeSearch.approvedAnswer(approved())).isTrue();
        for (var change : Map.of("status", "SUPERSEDED", "answer_visibility", "private",
                "evidence_review", "pending", "source_type", "CAREER_PRIVATE", "retrieval_eligible", false).entrySet()) {
            var meta = approved();
            meta.put(change.getKey(), change.getValue());
            assertThat(SupabaseCurrentKnowledgeSearch.approvedAnswer(meta)).isFalse();
        }
        for (var key : approved().keySet()) {
            var meta = approved(); meta.remove(key);
            assertThat(SupabaseCurrentKnowledgeSearch.approvedAnswer(meta)).isFalse();
        }
        assertThat(SupabaseCurrentKnowledgeSearch.approvedAnswer(null)).isFalse();
    }

    @Test void onlyExplicitlyPublicPublishedContentTypesEnterDocumentEvidence() {
        var meta = new HashMap<String, Object>(Map.of("source_type", "LIFE_BLOG", "status", "ACTIVE",
                "answer_visibility", "public", "retrieval_eligible", true));
        assertThat(PublicProfileEvidenceService.publicDocument(meta)).isTrue();
        for (var change : Map.of("status", "SUPERSEDED", "answer_visibility", "private",
                "retrieval_eligible", false, "source_type", "CAREER_PRIVATE").entrySet()) {
            var altered = new HashMap<>(meta); altered.put(change.getKey(), change.getValue());
            assertThat(PublicProfileEvidenceService.publicDocument(altered)).isFalse();
        }
        meta.remove("answer_visibility");
        assertThat(PublicProfileEvidenceService.publicDocument(meta)).isFalse();
    }

    @Test void profileQueriesOnlyApprovedSnippetsAndDoesNotExposeMetadata() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var pending = approved(); pending.put("evidence_review", "pending");
        var approved = approved(); approved.put("secret", "do-not-expose");
        approved.put("source_id", "10000000-0000-0000-0000-000000000001");
        approved.put("original_content_md5", "900150983cd24fb0d6963f7d28e17f72");
        String body = json.writeValueAsString(List.of(Map.of("id", "20000000-0000-0000-0000-000000000002", "content", "Degree from Example University", "metadata", approved),
                Map.of("content", "unreviewed", "metadata", pending)));
        var builder = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getPath()).isEqualTo("/rest/v1/kb_documents");
            if (request.url().getQuery().contains("id=in.")) {
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("""
                        [{"id":"10000000-0000-0000-0000-000000000001","content":"abc","metadata":{"source":"personal_profile"}}]
                        """).build());
            }
            assertThat(request.url().getQuery()).contains("eq.PROFILE", "eq.approved", "eq.ACTIVE", "eq.public", "eq.true");
            assertThat(request.headers().getFirst("apikey")).isEqualTo("test-key");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(body).build());
        });
        var service = service(builder);
        var result = service.profile();
        assertThat(result.get("status")).isEqualTo("EVIDENCE_FOUND");
        assertThat(result.toString()).contains("Example University").doesNotContain("unreviewed", "secret", "test-key");
    }

    @Test void sourceProjectionOmitsRestrictedIdsAndUrls() {
        var hit = new PublicProfileEvidenceService.ProfileRow("private-id", "Approved answer",
                Map.of("source_type", "OWNER_QA", "url", "https://host/private-source"));
        assertThat(PublicProfileEvidenceService.project(hit).toString())
                .contains("Approved answer").doesNotContain("private-id", "private-source");
    }

    @Test void missingConfigurationFailsClosed() {
        var service = new PublicProfileEvidenceService(WebClient.builder(), mock(SupabaseCurrentKnowledgeSearch.class));
        assertThatThrownBy(service::profile).isInstanceOf(IllegalStateException.class);
    }

    @Test void upstreamFailureIsNotNoEvidence() {
        var service = service(WebClient.builder().exchangeFunction(request ->
                Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build())));
        assertThatThrownBy(service::profile).isInstanceOf(RuntimeException.class);
    }

    @Test void sourceChangeRevokesOldApproval() throws Exception {
        var metadata = approved();
        metadata.put("source_id", "10000000-0000-0000-0000-000000000001");
        metadata.put("original_content_md5", "900150983cd24fb0d6963f7d28e17f72");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var body = json.writeValueAsString(List.of(Map.of("id", "20000000-0000-0000-0000-000000000002", "content", "Outdated degree", "metadata", metadata)));
        var service = service(WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(request.url().getQuery().contains("id=in.")
                                ? "[{\"id\":\"10000000-0000-0000-0000-000000000001\",\"content\":\"edited source\",\"metadata\":{\"source\":\"personal_profile\"}}]"
                                : body).build())));
        assertThat(service.profile().get("status")).isEqualTo("NO_EVIDENCE");
        assertThat(service.profile().toString()).doesNotContain("Outdated degree");
    }

    private PublicProfileEvidenceService service(WebClient.Builder builder) {
        var search = mock(SupabaseCurrentKnowledgeSearch.class);
        when(search.isEnabled()).thenReturn(true);
        var service = new PublicProfileEvidenceService(builder, search);
        ReflectionTestUtils.setField(service, "baseUrl", "https://example.test");
        ReflectionTestUtils.setField(service, "serviceKey", "test-key");
        return service;
    }
}
