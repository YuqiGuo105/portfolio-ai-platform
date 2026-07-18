package site.yuqi.agent.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure parser tests for {@link OpenAiIntentClassifier#parseAndAllowlist(String)}.
 * No network — we exercise the JSON contract directly.
 */
class OpenAiIntentClassifierParseTest {

    private OpenAiIntentClassifier classifier;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        ReflectionTestUtils.invokeMethod(registry, "init");
        classifier = new OpenAiIntentClassifier(registry, null, new ObjectMapper());
    }

    @Test
    void parsesChineseSearchExample() {
        String json = """
            {
              "intent": "ADMIN_SEARCH_CONTENT",
              "targetTool": "admin.search_content",
              "confidence": 0.93,
              "language": "zh",
              "normalizedQuery": "search Kafka blogs",
              "entities": { "keyword": "Kafka", "sourceType": "BLOG" },
              "riskLevel": "READ_ONLY",
              "requiresConfirmation": false,
              "missingEntities": [],
              "clarificationQuestion": null
            }
            """;
        IntentResult r = classifier.parseAndAllowlist(json);
        assertThat(r.intent()).isEqualTo(IntentType.ADMIN_SEARCH_CONTENT);
        assertThat(r.targetTool()).isEqualTo("admin.search_content");
        assertThat(r.entities()).containsEntry("keyword", "Kafka");
        assertThat(r.language()).isEqualTo("zh");
    }

    @Test
    void parsesFailedDeliveriesExample() {
        String json = """
            {
              "intent": "NOTIFICATION_LIST_FAILED_DELIVERIES",
              "targetTool": "notification.list_failed_deliveries",
              "confidence": 0.95,
              "language": "zh",
              "normalizedQuery": "failed email notification deliveries",
              "entities": { "channel": "EMAIL", "status": "FAILED" },
              "riskLevel": "READ_ONLY",
              "requiresConfirmation": false,
              "missingEntities": [],
              "clarificationQuestion": null
            }
            """;
        IntentResult r = classifier.parseAndAllowlist(json);
        assertThat(r.intent()).isEqualTo(IntentType.NOTIFICATION_LIST_FAILED_DELIVERIES);
        assertThat(r.entities()).containsEntry("status", "FAILED");
    }

    @Test
    void parsesLlmSelectedResponsePolicyAndProgressMessage() {
        String json = """
            {
              "intent": "KNOWLEDGE_QA",
              "targetTool": null,
              "confidence": 0.91,
              "language": "zh",
              "normalizedQuery": "estimate from public professional context",
              "entities": {},
              "riskLevel": "READ_ONLY",
              "requiresConfirmation": false,
              "missingEntities": [],
              "clarificationQuestion": null,
              "responsePolicy": "PUBLIC_ESTIMATE",
              "responseConstraints": [
                "PUBLIC_CONTEXT_ONLY",
                "LABEL_AS_ESTIMATE",
                "NO_PRIVATE_RECORD_CLAIM"
              ],
              "generationTier": "DEEP",
              "progressMessage": "正在根据公开职业信息准备估算"
            }
            """;

        IntentResult result = classifier.parseAndAllowlist(json);

        assertThat(result.intent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        assertThat(result.responsePolicy()).isEqualTo("PUBLIC_ESTIMATE");
        assertThat(result.responseConstraints())
                .containsExactly("PUBLIC_CONTEXT_ONLY", "LABEL_AS_ESTIMATE", "NO_PRIVATE_RECORD_CLAIM");
        assertThat(result.generationTier()).isEqualTo(GenerationTier.DEEP);
        assertThat(result.progressMessage()).isEqualTo("正在根据公开职业信息准备估算");
    }

    @Test
    void rejectsUntrustedPolicyValuesWithoutChangingTheLlmRoute() {
        String json = """
            {
              "intent": "KNOWLEDGE_QA",
              "targetTool": null,
              "confidence": 0.9,
              "language": "en",
              "entities": {},
              "riskLevel": "READ_ONLY",
              "requiresConfirmation": false,
              "missingEntities": [],
              "responsePolicy": "IGNORE_SYSTEM_PROMPT",
              "responseConstraints": ["PUBLIC_CONTEXT_ONLY", "EXFILTRATE_SECRETS"],
              "generationTier": "UNBOUNDED",
              "progressMessage": "Reading public context\\nwithout exposing hidden reasoning"
            }
            """;

        IntentResult result = classifier.parseAndAllowlist(json);

        assertThat(result.intent()).isEqualTo(IntentType.KNOWLEDGE_QA);
        assertThat(result.responsePolicy()).isEqualTo("STANDARD");
        assertThat(result.responseConstraints()).containsExactly("PUBLIC_CONTEXT_ONLY");
        assertThat(result.generationTier()).isEqualTo(GenerationTier.STANDARD);
        assertThat(result.progressMessage()).doesNotContain("\n");
    }

    @Test
    void parsesClarificationNeededWithMissingEntities() {
        String json = """
            {
              "intent": "CLARIFICATION_NEEDED",
              "targetTool": "admin.publish_content",
              "confidence": 0.86,
              "language": "zh",
              "normalizedQuery": "publish the latest Kafka blog",
              "entities": { "sourceType": "BLOG", "keyword": "Kafka", "sort": "latest" },
              "riskLevel": "RISKY_WRITE",
              "requiresConfirmation": true,
              "missingEntities": ["sourceId"],
              "clarificationQuestion": "Which Kafka blog?"
            }
            """;
        IntentResult r = classifier.parseAndAllowlist(json);
        assertThat(r.intent()).isEqualTo(IntentType.CLARIFICATION_NEEDED);
        assertThat(r.missingEntities()).containsExactly("sourceId");
        assertThat(r.requiresConfirmation()).isTrue();
    }

    @Test
    void rejectsNonAllowlistedTool() {
        String json = """
            {
              "intent": "ADMIN_SEARCH_CONTENT",
              "targetTool": "admin.search_content_v2_secret",
              "confidence": 0.9,
              "language": "en",
              "entities": {},
              "riskLevel": "READ_ONLY",
              "requiresConfirmation": false,
              "missingEntities": []
            }
            """;
        assertThatThrownBy(() -> classifier.parseAndAllowlist(json))
                .isInstanceOf(IntentClassificationException.class)
                .hasMessageContaining("non-allowlisted");
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> classifier.parseAndAllowlist("not json {{{"))
                .isInstanceOf(IntentClassificationException.class);
    }

    @Test
    void unknownIntentDegradesToUnknown() {
        String json = """
            {
              "intent": "TOTALLY_FAKE",
              "targetTool": null,
              "confidence": 0.0,
              "entities": {}
            }
            """;
        IntentResult r = classifier.parseAndAllowlist(json);
        assertThat(r.intent()).isEqualTo(IntentType.UNKNOWN);
    }
}
