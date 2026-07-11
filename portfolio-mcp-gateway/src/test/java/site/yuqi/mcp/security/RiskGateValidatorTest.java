package site.yuqi.mcp.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import site.yuqi.mcp.model.RiskLevel;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskGateValidatorTest {

    private RiskGateValidator validator;
    private ToolDefinition unsubscribeTool;

    @BeforeEach
    void setUp() {
        validator = new RiskGateValidator();
        unsubscribeTool = ToolDefinition.builder()
                .name("subscription.confirm_unsubscribe")
                .mode(ToolMode.WRITE)
                .riskLevel(RiskLevel.HIGH)
                .confirmRequired(false)
                .confirmationMethod("email_otp")
                .build();
    }

    @Test
    void acceptsEmailOtpAsTheUserConfirmation() {
        RiskGateValidator.Outcome outcome = validator.check(unsubscribeTool, Map.of(
                "verificationId", "ver_123",
                "verificationCode", "123456"));

        assertThat(outcome.allowed()).isTrue();
    }

    @Test
    void rejectsEmailOtpToolWithoutVerificationId() {
        RiskGateValidator.Outcome outcome = validator.check(unsubscribeTool, Map.of(
                "verificationCode", "123456"));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).contains("verificationId");
    }

    @Test
    void rejectsInvalidEmailOtpCode() {
        RiskGateValidator.Outcome outcome = validator.check(unsubscribeTool, Map.of(
                "verificationId", "ver_123",
                "verificationCode", "abc"));

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reason()).contains("6-digit");
    }

    @Test
    void acceptsValidEmailOtpTool() {
        RiskGateValidator.Outcome outcome = validator.check(unsubscribeTool, Map.of(
                "verificationId", "ver_123",
                "verificationCode", "123456"));

        assertThat(outcome.allowed()).isTrue();
    }
}
