package site.yuqi.agent.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import site.yuqi.agent.observability.EventRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SafetyServiceTest {

    private SafetyService service;

    @Test
    void configuredGatewayReceivesSafetyRequestsWithHeaderAuthentication() throws Exception {
        var mapper = new ObjectMapper();
        String decision = "{\"verdict\":\"PASS\",\"category\":\"SAFE\",\"confidence\":1,\"reason\":\"Safe\",\"constraints\":[]}";
        String response = mapper.writeValueAsString(java.util.Map.of("candidates", java.util.List.of(
                java.util.Map.of("content", java.util.Map.of("parts", java.util.List.of(java.util.Map.of("text", decision)))))));
        var gatewayService = new SafetyService(WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().toString()).isEqualTo("https://budget.example/ai/v1beta/models/test-model:generateContent");
            assertThat(request.headers().getFirst("x-goog-api-key")).isEqualTo("internal-gateway-token");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json").body(response).build());
        }), mock(EventRecorder.class), mapper);
        ReflectionTestUtils.setField(gatewayService, "enabled", true);
        ReflectionTestUtils.setField(gatewayService, "geminiBaseUrl", "https://budget.example/ai/v1beta");
        ReflectionTestUtils.setField(gatewayService, "geminiApiKey", "internal-gateway-token");
        ReflectionTestUtils.setField(gatewayService, "safetyModel", "test-model");
        ReflectionTestUtils.setField(gatewayService, "safetyTimeoutMs", 1000);
        assertThat(gatewayService.checkInput("Hello", java.util.UUID.randomUUID()).verdict()).isEqualTo(SafetyVerdict.PASS);
    }

    @BeforeEach
    void setUp() {
        service = new SafetyService(WebClient.builder(), mock(EventRecorder.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "inputBlockConfidenceThreshold", 0.95);
    }

    @Test
    void parsesAndAllowListsStructuredModelDecision() throws Exception {
        SafetyCheckResult result = service.parseModelResult("""
                {
                  "verdict": "WARN",
                  "category": "AMBIGUOUS",
                  "confidence": 0.76,
                  "reason": "Downstream constraints are required.",
                  "constraints": [
                    "PUBLIC_INFORMATION_ONLY",
                    "DOWNSTREAM_POLICY_REVIEW",
                    "UNTRUSTED_MODEL_INSTRUCTION"
                  ]
                }
                """, "input");

        assertThat(result.verdict()).isEqualTo(SafetyVerdict.WARN);
        assertThat(result.category()).isEqualTo("AMBIGUOUS");
        assertThat(result.confidence()).isEqualTo(0.76);
        assertThat(result.constraints())
                .containsExactly("PUBLIC_INFORMATION_ONLY", "DOWNSTREAM_POLICY_REVIEW");
    }

    @Test
    void preservesLanguageMismatchForBoundedRewrite() throws Exception {
        SafetyCheckResult result = service.parseModelResult("""
                {"verdict":"WARN","category":"LANGUAGE_MISMATCH","confidence":0.99,
                 "reason":"The current question requires Chinese, but the answer is English.","constraints":[]}
                """, "output_ctx");
        assertThat(result.verdict()).isEqualTo(SafetyVerdict.WARN);
        assertThat(result.category()).isEqualTo("LANGUAGE_MISMATCH");
        String prompt = (String) ReflectionTestUtils.getField(SafetyService.class, "CONTEXT_AWARE_OUTPUT_PROMPT");
        assertThat(prompt).contains("Language mismatch alone is never BLOCK", "explicit output-language request",
                "Prioritize safety/grounding violations over language mismatches");
    }

    @Test
    void aFailedLanguageCheckCannotReturnPassOrOverrideASafetyBlock() throws Exception {
        String json = """
                {"verdict":"%s","category":"%s","confidence":0.99,
                 "reason":"Review required","constraints":[],"languageMatches":false,"responseLanguage":"zh"}
                """;
        var inconsistent = service.parseModelResult(json.formatted("PASS", "SAFE"), "output_ctx");
        assertThat(inconsistent.verdict()).isEqualTo(SafetyVerdict.WARN);
        assertThat(inconsistent.category()).isEqualTo("LANGUAGE_MISMATCH");
        assertThat(inconsistent.reason()).contains("Required language: zh");
        var blocked = service.parseModelResult(json.formatted("BLOCK", "PROTECTED_DATA_ACCESS"), "output_ctx");
        assertThat(blocked.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(blocked.category()).isEqualTo("PROTECTED_DATA_ACCESS");
    }

    @Test
    void downgradesLowConfidenceInputBlockToWarn() throws Exception {
        SafetyCheckResult raw = service.parseModelResult("""
                {
                  "verdict": "BLOCK",
                  "category": "PROTECTED_DATA_ACCESS",
                  "confidence": 0.72,
                  "reason": "The intent is not explicit.",
                  "constraints": []
                }
                """, "input");

        SafetyCheckResult result = service.enforceInputBlockThreshold(raw);

        assertThat(result.verdict()).isEqualTo(SafetyVerdict.WARN);
        assertThat(result.category()).isEqualTo("PROTECTED_DATA_ACCESS");
        assertThat(result.constraints()).contains("DOWNSTREAM_POLICY_REVIEW");
    }

    @Test
    void preservesHighConfidenceInputBlock() throws Exception {
        SafetyCheckResult raw = service.parseModelResult("""
                {
                  "verdict": "BLOCK",
                  "category": "HARMFUL_ACTION",
                  "confidence": 0.97,
                  "reason": "The prohibited action is explicit.",
                  "constraints": []
                }
                """, "input");

        SafetyCheckResult result = service.enforceInputBlockThreshold(raw);

        assertThat(result.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(result.confidence()).isEqualTo(0.97);
    }

    @Test
    void downgradesBorderlineActionBlockForDownstreamPolicyReview() throws Exception {
        SafetyCheckResult raw = service.parseModelResult("""
                {
                  "verdict": "BLOCK",
                  "category": "HARMFUL_ACTION",
                  "confidence": 0.90,
                  "reason": "The request creates an external side effect.",
                  "constraints": []
                }
                """, "input");

        SafetyCheckResult result = service.enforceInputBlockThreshold(raw);

        assertThat(result.verdict()).isEqualTo(SafetyVerdict.WARN);
        assertThat(result.constraints()).contains("DOWNSTREAM_POLICY_REVIEW");
    }
}
