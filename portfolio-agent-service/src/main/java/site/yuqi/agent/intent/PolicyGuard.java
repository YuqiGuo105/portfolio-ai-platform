package site.yuqi.agent.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Role-based authorization + write-confirmation gating.
 *
 * <p>Permissions are mapped per tool. Roles are read from
 * {@link IntentRequest#getUserRoles()} (comma-separated, e.g.
 * {@code VIEWER,EDITOR}).
 *
 * <p>Anonymous callers (no userId / no roles) get implicit {@code VIEWER}
 * for public READ_ONLY tools only — never for writes.
 */
@Slf4j
@Component
public class PolicyGuard {

    public enum Role { VIEWER, EDITOR, PUBLISHER, ADMIN }

    /** Per-tool minimum role. Missing entry → ADMIN-only (deny-by-default). */
    private static final Map<String, Role> REQUIRED_ROLE = Map.ofEntries(
            Map.entry("admin.search_content",              Role.VIEWER),
            Map.entry("admin.get_content",                 Role.VIEWER),
            Map.entry("admin.list_indexing_jobs",          Role.VIEWER),
            Map.entry("admin.list_outbox_events",          Role.VIEWER),
            Map.entry("admin.create_content_draft",        Role.EDITOR),
            Map.entry("admin.update_content",              Role.EDITOR),
            Map.entry("admin.publish_content",             Role.PUBLISHER),
            Map.entry("admin.reindex_rag",                 Role.PUBLISHER),
            Map.entry("admin.reindex_search",              Role.PUBLISHER),
            Map.entry("admin.retry_indexing_job",          Role.ADMIN),

            Map.entry("notification.get_delivery_stats",   Role.VIEWER),
            Map.entry("notification.list_notifications",   Role.VIEWER),
            Map.entry("notification.list_failed_deliveries", Role.VIEWER),
            Map.entry("notification.list_subscribers",     Role.ADMIN),
            Map.entry("notification.get_subscriber",       Role.ADMIN),
            Map.entry("notification.retry_failed_delivery",Role.ADMIN),
            Map.entry("notification.send_test_notification", Role.ADMIN),
            Map.entry("notification.update_subscription",  Role.ADMIN),
            Map.entry("notification.unsubscribe_subscriber", Role.ADMIN)
    );

    public PolicyDecision check(ToolDefinition tool,
                                Map<String, Object> resolvedArgs,
                                IntentRequest request) {
        Role required = REQUIRED_ROLE.getOrDefault(tool.name(), Role.ADMIN);
        Set<Role> userRoles = parseRoles(request.getUserRoles());

        // Anonymous callers get implicit VIEWER for public reads only.
        if (userRoles.isEmpty() && tool.riskLevel() == RiskLevel.READ_ONLY && required == Role.VIEWER) {
            userRoles = Set.of(Role.VIEWER);
        }

        if (!hasRole(userRoles, required)) {
            return PolicyDecision.builder()
                    .allowed(false)
                    .reason("Tool " + tool.name() + " requires " + required + " role.")
                    .build();
        }

        boolean confirm = tool.requiresConfirmation()
                || tool.riskLevel() == RiskLevel.RISKY_WRITE
                || tool.riskLevel() == RiskLevel.DESTRUCTIVE;

        return PolicyDecision.builder()
                .allowed(true)
                .requiresConfirmation(confirm)
                .preview(buildPreview(tool, resolvedArgs))
                .build();
    }

    private static Set<Role> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<Role> out = new HashSet<>();
        for (String tok : csv.split(",")) {
            try {
                out.add(Role.valueOf(tok.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) { /* skip unknown */ }
        }
        return out;
    }

    /** ADMIN implies PUBLISHER implies EDITOR implies VIEWER. */
    private static boolean hasRole(Set<Role> userRoles, Role required) {
        if (userRoles.contains(Role.ADMIN)) return true;
        if (required == Role.ADMIN) return false;
        if (userRoles.contains(Role.PUBLISHER)) return true;
        if (required == Role.PUBLISHER) return false;
        if (userRoles.contains(Role.EDITOR)) return true;
        if (required == Role.EDITOR) return false;
        return userRoles.contains(Role.VIEWER);
    }

    private static String buildPreview(ToolDefinition tool, Map<String, Object> args) {
        return switch (tool.intent()) {
            case ADMIN_PUBLISH_CONTENT -> "About to publish content " + args.get("sourceType") + "/" + args.get("sourceId")
                    + ". This creates a new content_version, emits an outbox event, and triggers RAG + Search indexing. Confirm?";
            case ADMIN_REINDEX_RAG -> "About to reindex " + args.get("sourceType") + "/" + args.get("sourceId")
                    + " into the RAG vector store. Confirm?";
            case ADMIN_REINDEX_SEARCH -> "About to reindex " + args.get("sourceType") + "/" + args.get("sourceId")
                    + " into OpenSearch. Confirm?";
            case ADMIN_RETRY_INDEXING_JOB -> "Retry indexing job " + args.get("jobId") + "?";
            case NOTIFICATION_UNSUBSCRIBE -> "Hard-unsubscribe subscriber " + args.get("subscriberId")
                    + "? Downstream OTP confirmation is required.";
            default -> "Run " + tool.name() + " with arguments " + args + "?";
        };
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PolicyDecision {
        private boolean allowed;
        private boolean requiresConfirmation;
        private String reason;
        private String preview;
    }
}
