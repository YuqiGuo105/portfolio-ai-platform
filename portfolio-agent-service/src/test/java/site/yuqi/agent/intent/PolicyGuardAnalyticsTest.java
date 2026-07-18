package site.yuqi.agent.intent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyGuardAnalyticsTest {

    private final PolicyGuard guard = new PolicyGuard();
    private final ToolDefinition analyticsTool = new ToolDefinition(
            "analytics.get_visitor_summary",
            IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
            "Get aggregate visitor metrics.",
            RiskLevel.READ_ONLY,
            false,
            Set.of(),
            Set.of("startDate", "endDate", "dimensions")
    );

    @Test
    void anonymousCanRunAggregateAnalyticsTools() {
        // Aggregate analytics is an explicit public read: PolicyGuard grants
        // implicit VIEWER to anonymous callers for tools in ANONYMOUS_READ_TOOLS.
        PolicyGuard.PolicyDecision decision = guard.check(
                analyticsTool,
                Map.of("startDate", "2026-06-30", "endDate", "2026-07-06"),
                IntentRequest.builder().sessionId("s1").utterance("recent visitors").build());

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.isRequiresConfirmation()).isFalse();
    }

    @Test
    void viewerCanRunAnalyticsWithoutConfirmation() {
        PolicyGuard.PolicyDecision decision = guard.check(
                analyticsTool,
                Map.of("startDate", "2026-06-30", "endDate", "2026-07-06"),
                IntentRequest.builder()
                        .sessionId("s1")
                        .utterance("recent visitors")
                        .userRoles("VIEWER")
                        .build());

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.isRequiresConfirmation()).isFalse();
    }

    @Test
    void anonymousCanRequestCodeAndOtpConfirmsTheStatusChange() {
        IntentRequest anonymous = IntentRequest.builder()
                .sessionId("s1")
                .utterance("unsubscribe")
                .build();
        ToolDefinition requestCode = new ToolDefinition(
                "subscription.request_unsubscribe_code",
                IntentType.SUBSCRIPTION_REQUEST_UNSUBSCRIBE_CODE,
                "Send code", RiskLevel.SAFE_WRITE, false,
                Set.of("email"), Set.of());
        ToolDefinition confirm = new ToolDefinition(
                "subscription.confirm_unsubscribe",
                IntentType.SUBSCRIPTION_CONFIRM_UNSUBSCRIBE,
                "Confirm unsubscribe", RiskLevel.RISKY_WRITE, false,
                Set.of("verificationId", "verificationCode"), Set.of());

        PolicyGuard.PolicyDecision requestDecision = guard.check(
                requestCode, Map.of("email", "a@example.com"), anonymous);
        PolicyGuard.PolicyDecision confirmDecision = guard.check(
                confirm, Map.of("verificationId", "v1", "verificationCode", "123456"), anonymous);

        assertThat(requestDecision.isAllowed()).isTrue();
        assertThat(requestDecision.isRequiresConfirmation()).isFalse();
        assertThat(requestDecision.getPreview()).doesNotContain("a@example.com");
        assertThat(confirmDecision.isAllowed()).isTrue();
        assertThat(confirmDecision.isRequiresConfirmation()).isFalse();
    }

    @Test
    void anonymousContactRequiresConfirmationAndMasksReplyAddress() {
        ToolDefinition contact = new ToolDefinition(
                "contact.email_owner",
                IntentType.CONTACT_EMAIL_OWNER,
                "Send a message", RiskLevel.SAFE_WRITE, true,
                Set.of("email", "message"), Set.of("name"));

        PolicyGuard.PolicyDecision decision = guard.check(
                contact,
                Map.of("email", "visitor@example.com", "message", "Hello world"),
                IntentRequest.builder().sessionId("s1").utterance("send it").build());

        assertThat(decision.isAllowed()).isTrue();
        assertThat(decision.isRequiresConfirmation()).isTrue();
        assertThat(decision.getPreview())
                .contains("v***@example.com", "Hello world")
                .doesNotContain("visitor@example.com");
    }

    @Test
    void alertPolicyToolsRequireAdminAndApplyRequiresConfirmation() {
        ToolDefinition prepare = new ToolDefinition(
                "alerts.prepare_change",
                IntentType.ALERTS_PREPARE_CHANGE,
                "Prepare alert change", RiskLevel.READ_ONLY, false,
                Set.of("action", "patch", "reason"), Set.of("ruleId"));
        ToolDefinition apply = new ToolDefinition(
                "alerts.apply_change",
                IntentType.ALERTS_APPLY_CHANGE,
                "Apply alert change", RiskLevel.RISKY_WRITE, true,
                Set.of("changeId"), Set.of());

        IntentRequest viewer = IntentRequest.builder()
                .sessionId("viewer-session")
                .utterance("change alert rule")
                .userRoles("VIEWER")
                .build();
        IntentRequest admin = IntentRequest.builder()
                .sessionId("admin-session")
                .utterance("change alert rule")
                .userRoles("VIEWER,EDITOR,PUBLISHER,ADMIN")
                .build();

        assertThat(guard.check(prepare, Map.of(), viewer).isAllowed()).isFalse();
        assertThat(guard.check(prepare, Map.of(), admin).isAllowed()).isTrue();

        PolicyGuard.PolicyDecision applyDecision = guard.check(
                apply, Map.of("changeId", "chg_123"), admin);
        assertThat(applyDecision.isAllowed()).isTrue();
        assertThat(applyDecision.isRequiresConfirmation()).isTrue();
        assertThat(applyDecision.getPreview()).contains("chg_123");
    }
}
