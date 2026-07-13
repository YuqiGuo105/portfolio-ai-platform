package site.yuqi.agent.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.agent.observability.EventRecorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SafetyServiceTest {

    private SafetyService service;

    @BeforeEach
    void setUp() {
        service = new SafetyService(WebClient.builder(), mock(EventRecorder.class), new ObjectMapper());
        ReflectionTestUtils.setField(service, "inputBlockConfidenceThreshold", 0.90);
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
}
