package site.yuqi.mcp.security;

import org.springframework.stereotype.Component;
import site.yuqi.mcp.model.RiskLevel;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;

import java.util.Map;

/**
 * Enforces the tool's risk policy on each invoke:
 *
 * <ul>
 *   <li>{@code confirmRequired: true} forces callers to pass
 *       {@code _confirmed=true} (or, when the tool supports it, a valid
 *       confirmation token / dry-run flag).</li>
 *   <li>{@code HIGH} / {@code CRITICAL} writes require an explicit
 *       {@code _confirmed=true} OR {@code dryRun=true}.</li>
 *   <li>Read-mode tools are always allowed.</li>
 * </ul>
 */
@Component
public class RiskGateValidator {

    public Outcome check(ToolDefinition tool, Map<String, Object> args) {
        if ("email_otp".equalsIgnoreCase(tool.getConfirmationMethod())) {
            Outcome otp = checkEmailOtp(tool, args);
            if (!otp.allowed()) return otp;
        }

        if (tool.getMode() == ToolMode.READ) {
            return Outcome.ok();
        }

        boolean confirmed = Boolean.TRUE.equals(args.get("_confirmed"));
        boolean dryRun = Boolean.TRUE.equals(args.get("dryRun"));

        RiskLevel risk = tool.getRiskLevel() == null ? RiskLevel.LOW : tool.getRiskLevel();

        if ((risk == RiskLevel.HIGH || risk == RiskLevel.CRITICAL) && !confirmed && !dryRun) {
            return Outcome.fail("Tool " + tool.getName() + " is " + risk
                    + " and requires _confirmed=true or dryRun=true.");
        }

        if (tool.isConfirmRequired() && !confirmed && !dryRun) {
            return Outcome.fail("Tool " + tool.getName() + " requires _confirmed=true.");
        }
        return Outcome.ok();
    }

    private Outcome checkEmailOtp(ToolDefinition tool, Map<String, Object> args) {
        boolean dryRun = Boolean.TRUE.equals(args.get("dryRun"));
        if (dryRun && tool.isDryRunSupported()) {
            return Outcome.ok();
        }
        if (!Boolean.TRUE.equals(args.get("_confirmed"))) {
            return Outcome.fail("Tool " + tool.getName()
                    + " requires explicit user confirmation before email-OTP execution.");
        }

        Object verificationId = args.get("verificationId");
        if (!(verificationId instanceof String id) || id.isBlank()) {
            return Outcome.fail("Tool " + tool.getName()
                    + " requires verificationId from the email verification request.");
        }

        Object code = args.get("verificationCode");
        if (!(code instanceof String s) || !s.matches("\\d{6}")) {
            return Outcome.fail("Tool " + tool.getName()
                    + " requires a 6-digit email verificationCode.");
        }

        return Outcome.ok();
    }

    public record Outcome(boolean allowed, String reason) {
        public static Outcome ok() { return new Outcome(true, null); }
        public static Outcome fail(String reason) { return new Outcome(false, reason); }
    }
}
