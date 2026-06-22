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
