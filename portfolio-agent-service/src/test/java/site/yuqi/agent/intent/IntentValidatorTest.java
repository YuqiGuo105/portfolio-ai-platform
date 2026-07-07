package site.yuqi.agent.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validator unit tests covering reject / clarify / execute paths.
 *
 * <p>Maps to the spec's tests #3 (missing sourceId), #4 (missing jobId),
 * #6 (write must require confirmation), #7 (invalid tool), #8 (mismatched
 * intent/tool), #9 (low confidence).
 */
class IntentValidatorTest {

    private ToolRegistry registry;
    private IntentValidator validator;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        ReflectionTestUtils.invokeMethod(registry, "init");
        validator = new IntentValidator(registry);
        ReflectionTestUtils.setField(validator, "readThreshold", 0.85);
        ReflectionTestUtils.setField(validator, "clarifyThreshold", 0.65);
    }

    @Test
    void rejectsToolNotInRegistry() {
        IntentResult r = new IntentResult(
                IntentType.ADMIN_SEARCH_CONTENT, "admin.totally_made_up", 0.99,
                "en", "x", Map.of("keyword", "kafka"),
                RiskLevel.READ_ONLY, false, List.of(), null);
        IntentValidator.ValidationResult v = validator.validate(r);
        assertThat(v.getStatus()).isEqualTo(IntentValidator.Status.REJECT);
    }

    @Test
    void rejectsMismatchedIntentToolPair() {
        IntentResult r = new IntentResult(
                IntentType.ADMIN_PUBLISH_CONTENT, "admin.search_content", 0.99,
                "en", "x", Map.of("keyword", "kafka"),
                RiskLevel.READ_ONLY, false, List.of(), null);
        assertThat(validator.validate(r).getStatus()).isEqualTo(IntentValidator.Status.REJECT);
    }

    @Test
    void rejectsWriteWithoutConfirmationFlag() {
        IntentResult r = new IntentResult(
                IntentType.ADMIN_PUBLISH_CONTENT, "admin.publish_content", 0.95,
                "en", "publish blog 1", Map.of("sourceType", "BLOG", "sourceId", 1L),
                RiskLevel.RISKY_WRITE, false, List.of(), null);
        assertThat(validator.validate(r).getStatus()).isEqualTo(IntentValidator.Status.REJECT);
    }

    @Test
    void clarifyWhenSourceIdMissingForPublish() {
        IntentResult r = new IntentResult(
                IntentType.ADMIN_PUBLISH_CONTENT, "admin.publish_content", 0.88,
                "zh", "publish the latest Kafka blog",
                Map.of("sourceType", "BLOG", "keyword", "Kafka"),
                RiskLevel.RISKY_WRITE, true, List.of("sourceId"), null);
        IntentValidator.ValidationResult v = validator.validate(r);
        assertThat(v.getStatus()).isEqualTo(IntentValidator.Status.CLARIFY);
        assertThat(v.getMissingEntities()).contains("sourceId");
    }

    @Test
    void clarifyWhenJobIdMissingForRetry() {
        IntentResult r = new IntentResult(
                IntentType.ADMIN_RETRY_INDEXING_JOB, "admin.retry_indexing_job", 0.78,
                "zh", "retry failed indexing job", Map.of("status", "FAILED"),
                RiskLevel.SAFE_WRITE, true, List.of("jobId"), null);
        IntentValidator.ValidationResult v = validator.validate(r);
        assertThat(v.getStatus()).isEqualTo(IntentValidator.Status.CLARIFY);
        assertThat(v.getMissingEntities()).contains("jobId");
    }

    @Test
    void clarifyOnLowReadConfidence() {
        IntentResult r = new IntentResult(
                IntentType.ADMIN_SEARCH_CONTENT, "admin.search_content", 0.40,
                "en", "kafka", Map.of("keyword", "kafka"),
                RiskLevel.READ_ONLY, false, List.of(), null);
        assertThat(validator.validate(r).getStatus()).isEqualTo(IntentValidator.Status.CLARIFY);
    }

    @Test
    void executesHighConfidenceReadOnly() {
        IntentResult r = new IntentResult(
                IntentType.NOTIFICATION_LIST_FAILED_DELIVERIES,
                "notification.list_failed_deliveries", 0.95,
                "zh", "failed email deliveries", Map.of("channel", "EMAIL", "status", "FAILED"),
                RiskLevel.READ_ONLY, false, List.of(), null);
        IntentValidator.ValidationResult v = validator.validate(r);
        assertThat(v.getStatus()).isEqualTo(IntentValidator.Status.EXECUTE);
        assertThat(v.getTool().name()).isEqualTo("notification.list_failed_deliveries");
    }

    @Test
    void executesAnalyticsReadToolThatRequiresTimeRangeConfirmation() {
        IntentResult r = new IntentResult(
                IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
                "analytics.get_visitor_summary", 0.95,
                "en", "recent visitors", Map.of("timeRangePreset", "recent"),
                RiskLevel.READ_ONLY, true, List.of(), null);
        IntentValidator.ValidationResult v = validator.validate(r);
        assertThat(v.getStatus()).isEqualTo(IntentValidator.Status.EXECUTE);
        assertThat(v.getTool().requiresConfirmation()).isTrue();
    }

    @Test
    void generalChatPassesThrough() {
        IntentResult r = new IntentResult(
                IntentType.GENERAL_CHAT, null, 0.5,
                "en", null, Map.of(), RiskLevel.READ_ONLY, false, List.of(), null);
        assertThat(validator.validate(r).getStatus()).isEqualTo(IntentValidator.Status.GENERAL_CHAT);
    }

    @Test
    void clarificationNeededIsClarify() {
        IntentResult r = new IntentResult(
                IntentType.CLARIFICATION_NEEDED, "admin.publish_content", 0.86,
                "zh", "publish the latest Kafka blog",
                Map.of("sourceType", "BLOG", "keyword", "Kafka"),
                RiskLevel.RISKY_WRITE, true, List.of("sourceId"),
                "Which Kafka blog?");
        IntentValidator.ValidationResult v = validator.validate(r);
        assertThat(v.getStatus()).isEqualTo(IntentValidator.Status.CLARIFY);
        assertThat(v.getMessage()).contains("Kafka");
    }
}
