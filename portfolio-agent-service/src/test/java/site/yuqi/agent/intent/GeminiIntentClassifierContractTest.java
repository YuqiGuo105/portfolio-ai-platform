package site.yuqi.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class GeminiIntentClassifierContractTest {
    final ObjectMapper mapper = new ObjectMapper();
    final AtomicReference<org.springframework.web.reactive.function.client.ClientRequest> sent = new AtomicReference<>();
    GeminiIntentClassifier classifier;
    String response;
    HttpStatus status;
    final String route = """
            {"intent":"KNOWLEDGE_QA","targetTool":null,"language":"zh","confidence":0.9,
             "entities":{},"riskLevel":"READ_ONLY","generationTier":"DEEP"}
            """;

    @BeforeEach void setup() throws Exception {
        var registry = new ToolRegistry();
        ReflectionTestUtils.invokeMethod(registry, "init");
        status = HttpStatus.OK;
        response = mapper.writeValueAsString(Map.of("candidates", List.of(Map.of("content", Map.of("parts", List.of(Map.of("text", route)))))));
        classifier = new GeminiIntentClassifier(registry, WebClient.builder().exchangeFunction(request -> {
            sent.set(request);
            return Mono.just(ClientResponse.create(status).header("Content-Type", "application/json").body(response).build());
        }), mapper);
        ReflectionTestUtils.setField(classifier, "apiKey", "test-provider-secret");
        ReflectionTestUtils.setField(classifier, "baseUrl", "https://provider.example.test/v1beta");
        ReflectionTestUtils.setField(classifier, "defaultModel", "test-model");
        ReflectionTestUtils.setField(classifier, "timeoutMs", 1000);
        ReflectionTestUtils.setField(classifier, "maxOutputTokens", 1024);
    }

    IntentRequest request() { return IntentRequest.builder().sessionId("test-session").utterance("Explain the project").build(); }

    @Test void sendsKeyOnlyInHeaderAndPreservesStructuredRoute() {
        var result = classifier.classify(request());
        assertThat(result.intent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        assertThat(result.generationTier()).isEqualTo(GenerationTier.DEEP);
        assertThat(result.language()).isEqualTo("zh");
        assertThat(sent.get().headers().getFirst("x-goog-api-key")).isEqualTo("test-provider-secret");
        assertThat(sent.get().url().toString()).doesNotContain("key=", "test-provider-secret");
    }

    @Test void escalationAndReviewUseConfiguredModels() {
        var first = classifier.parseAndAllowlist(route);
        assertThat(classifier.escalate(request(), first)).isSameAs(first);
        ReflectionTestUtils.setField(classifier, "escalationModel", "review-model");
        assertThat(classifier.escalate(request(), first).intent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        assertThat(sent.get().url().getPath()).contains("review-model");
        assertThat(classifier.reviewRoute(request(), first).intent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        ReflectionTestUtils.setField(classifier, "escalationModel", "");
        classifier.reviewRoute(request(), first);
        assertThat(sent.get().url().getPath()).contains("test-model");
    }

    @Test void providerErrorsNeverExposeResponseOrCredentials() {
        status = HttpStatus.BAD_GATEWAY;
        response = "test-provider-secret private provider response";
        assertThatThrownBy(() -> classifier.classify(request()))
                .isInstanceOf(IntentClassificationException.class)
                .hasMessage("Gemini provider unavailable.").hasNoCause();
        ReflectionTestUtils.setField(classifier, "apiKey", "");
        assertThatThrownBy(() -> classifier.classify(request())).hasMessageContaining("not configured");
    }

    @Test void rejectsIncompleteProviderEnvelopes() {
        for (String body : List.of("{}", "{\"candidates\":[]}", "{\"candidates\":[{\"content\":{\"parts\":[]}}]}",
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]}}]}")) {
            response = body;
            assertThatThrownBy(() -> classifier.classify(request())).isInstanceOf(IntentClassificationException.class);
        }
    }

    @Test void parsesNestedEntitiesAndClampsConfidenceWithoutAuthorizingWrites() {
        var result = classifier.parseAndAllowlist("""
                {"intent":"ADMIN_SEARCH_CONTENT","targetTool":"admin.search_content","confidence":2,
                 "entities":{"keyword":"Kafka","limit":5,"score":0.5,"enabled":true,
                    "patch":{"tags":["AI",2,false,null]}},
                 "riskLevel":"RISKY_WRITE","requiresConfirmation":true,
                 "missingEntities":["sourceId","",123],"responsePolicy":"GROUNDED"}
                """);
        assertThat(result.confidence()).isEqualTo(1);
        assertThat(result.entities()).containsEntry("keyword", "Kafka").containsEntry("limit", 5L);
        assertThat(result.entities().get("patch")).isInstanceOf(Map.class);
        assertThat(result.requiresConfirmation()).isTrue();
        assertThat(result.missingEntities()).containsExactly("sourceId");
        assertThat(classifier.parseAndAllowlist("{\"intent\":\"UNKNOWN_VALUE\",\"riskLevel\":\"INVALID\",\"confidence\":-2}").intent()).isEqualTo(IntentType.UNKNOWN);
    }

    @Test void rejectsMalformedJsonAndUnregisteredTools() {
        for (String body : List.of("secret malformed {{{", "[]", "{\"targetTool\":\"private.unregistered\"}")) {
            assertThatThrownBy(() -> classifier.parseAndAllowlist(body)).isInstanceOf(IntentClassificationException.class);
        }
        assertThatThrownBy(() -> classifier.parseAndAllowlist("secret malformed {{{"))
                .hasMessage("LLM did not return valid JSON.").hasNoCause();
    }

    @Test void boundsHistoryAndCarriesTrustedContextWithoutDroppingCurrentInput() throws Exception {
        var req = request();
        req.setPageContext(Map.of("path", "/mcp-guide"));
        req.setCompactSummary(Map.of("goal", "learn"));
        req.setStructuredState(Map.of("projectId", "known"));
        req.setPendingActionContext(Map.of("pending", true));
        req.setRecentMessages(java.util.stream.IntStream.range(0, 8).mapToObj(i -> Map.of("role", "user", "content", ("turn" + i).repeat(200))).toList());
        String prompt = ReflectionTestUtils.invokeMethod(classifier, "buildUserPrompt", req);
        var json = mapper.readTree(prompt);
        assertThat(json.get("utterance").asText()).isEqualTo(req.getUtterance());
        assertThat(json.get("recentMessages").size()).isEqualTo(6);
        assertThat(json.get("recentMessages").get(0).get("content").asText()).hasSize(600).startsWith("turn2");
        assertThat(json.has("compactSummary")).isTrue();
        assertThat(json.has("structuredState")).isTrue();
        assertThat(json.has("pendingAction")).isTrue();
    }
}
