package site.yuqi.agent.intent;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hardcoded allowlist of tools the MCP layer can dispatch to. The LLM is
 * shown this list in the system prompt and CANNOT select anything outside
 * it — {@link IntentValidator} re-checks at runtime.
 *
 * <p>Intentionally not data-driven in Sprint 1: keeping it in Java guarantees
 * the compiler enforces the intent ↔ tool mapping and prevents accidental
 * additions via config drift.
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolDefinition> byName = new LinkedHashMap<>();
    private final Map<IntentType, ToolDefinition> byIntent = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        // ── Admin: read ─────────────────────────────────────────────────
        register(new ToolDefinition(
                "admin.search_content",
                IntentType.ADMIN_SEARCH_CONTENT,
                "Search portfolio content such as blogs, projects, life blogs, and experience.",
                RiskLevel.READ_ONLY,
                false,
                Set.of("keyword"),
                Set.of("sourceType", "category", "limit")
        ));
        register(new ToolDefinition(
                "admin.get_content",
                IntentType.ADMIN_GET_CONTENT,
                "Get a single content item by sourceType and sourceId.",
                RiskLevel.READ_ONLY,
                false,
                Set.of("sourceType", "sourceId"),
                Set.of()
        ));
        register(new ToolDefinition(
                "admin.list_indexing_jobs",
                IntentType.ADMIN_LIST_INDEXING_JOBS,
                "List indexing jobs (RAG / OpenSearch) with optional status filter.",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("status", "kind", "limit")
        ));
        register(new ToolDefinition(
                "admin.list_outbox_events",
                IntentType.ADMIN_LIST_OUTBOX_EVENTS,
                "List recent outbox events awaiting or after Kafka emission.",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("status", "limit")
        ));

        // ── Analytics: privacy-safe aggregate reads ────────────────────
        register(new ToolDefinition(
                "analytics.get_visitor_summary",
                IntentType.ANALYTICS_GET_VISITOR_SUMMARY,
                "Get aggregate visitor metrics only. Never returns specific visitor identity, IP, email, session, or raw events.",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("startDate", "endDate", "granularity", "timeRangePreset", "dimensions")
        ));
        register(new ToolDefinition(
                "analytics.get_top_pages",
                IntentType.ANALYTICS_GET_TOP_PAGES,
                "Get top pages by aggregate visits only. Minimum confirmed analytics window is 7 days.",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("startDate", "endDate", "limit", "timeRangePreset")
        ));
        register(new ToolDefinition(
                "analytics.get_referrer_summary",
                IntentType.ANALYTICS_GET_REFERRER_SUMMARY,
                "Get aggregate traffic source and referrer summary only. Never returns individual visitor details.",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("startDate", "endDate", "timeRangePreset")
        ));

        // ── Admin: write ────────────────────────────────────────────────
        register(new ToolDefinition(
                "admin.create_content_draft",
                IntentType.ADMIN_CREATE_DRAFT,
                "Create a new content draft (no publish, no events emitted).",
                RiskLevel.SAFE_WRITE,
                true,
                Set.of("sourceType", "title"),
                Set.of("summary", "body", "category", "tags")
        ));
        register(new ToolDefinition(
                "admin.update_content",
                IntentType.ADMIN_UPDATE_CONTENT,
                "Update fields on an existing draft or published content item.",
                RiskLevel.SAFE_WRITE,
                true,
                Set.of("sourceType", "sourceId"),
                Set.of("title", "summary", "body", "category", "tags", "changeNote")
        ));
        register(new ToolDefinition(
                "admin.publish_content",
                IntentType.ADMIN_PUBLISH_CONTENT,
                "Publish a content item and trigger content versioning, outbox event, "
                        + "RAG indexing, OpenSearch indexing, and notification pipeline.",
                RiskLevel.RISKY_WRITE,
                true,
                Set.of("sourceType", "sourceId"),
                Set.of("changeNote")
        ));
        register(new ToolDefinition(
                "admin.reindex_rag",
                IntentType.ADMIN_REINDEX_RAG,
                "Reindex a content item into the RAG vector store.",
                RiskLevel.RISKY_WRITE,
                true,
                Set.of("sourceType", "sourceId"),
                Set.of("dryRun")
        ));
        register(new ToolDefinition(
                "admin.reindex_search",
                IntentType.ADMIN_REINDEX_SEARCH,
                "Reindex a content item into OpenSearch.",
                RiskLevel.RISKY_WRITE,
                true,
                Set.of("sourceType", "sourceId"),
                Set.of("dryRun")
        ));
        register(new ToolDefinition(
                "admin.retry_indexing_job",
                IntentType.ADMIN_RETRY_INDEXING_JOB,
                "Retry a failed indexing job by jobId.",
                RiskLevel.SAFE_WRITE,
                true,
                Set.of("jobId"),
                Set.of()
        ));

        // ── Notification: read ──────────────────────────────────────────
        register(new ToolDefinition(
                "notification.get_delivery_stats",
                IntentType.NOTIFICATION_GET_STATS,
                "Get aggregated notification delivery statistics.",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("window", "channel")
        ));
        register(new ToolDefinition(
                "notification.list_subscribers",
                IntentType.NOTIFICATION_LIST_SUBSCRIBERS,
                "List newsletter subscribers (admin-only).",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("status", "category", "limit", "cursor")
        ));
        register(new ToolDefinition(
                "notification.get_subscriber",
                IntentType.NOTIFICATION_GET_SUBSCRIBER,
                "Get a subscriber by id or email (admin-only).",
                RiskLevel.READ_ONLY,
                false,
                Set.of("subscriberId"),
                Set.of()
        ));
        register(new ToolDefinition(
                "notification.list_notifications",
                IntentType.NOTIFICATION_LIST_NOTIFICATIONS,
                "List sent / scheduled notifications.",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("status", "channel", "limit")
        ));
        register(new ToolDefinition(
                "notification.list_failed_deliveries",
                IntentType.NOTIFICATION_LIST_FAILED_DELIVERIES,
                "List failed or pending notification deliveries (email / web push).",
                RiskLevel.READ_ONLY,
                false,
                Set.of(),
                Set.of("channel", "status", "limit")
        ));

        // ── Notification: write ─────────────────────────────────────────
        register(new ToolDefinition(
                "notification.retry_failed_delivery",
                IntentType.NOTIFICATION_RETRY_FAILED_DELIVERY,
                "Retry a failed notification delivery by recipientId.",
                RiskLevel.SAFE_WRITE,
                true,
                Set.of("recipientId"),
                Set.of()
        ));
        register(new ToolDefinition(
                "notification.send_test_notification",
                IntentType.NOTIFICATION_SEND_TEST,
                "Send a test notification to a specific subscriber/email.",
                RiskLevel.SAFE_WRITE,
                true,
                Set.of("recipient"),
                Set.of("channel", "templateId")
        ));
        register(new ToolDefinition(
                "notification.update_subscription",
                IntentType.NOTIFICATION_UPDATE_SUBSCRIPTION,
                "Update subscriber preferences (categories / frequency).",
                RiskLevel.SAFE_WRITE,
                true,
                Set.of("subscriberId"),
                Set.of("categories", "frequency")
        ));
        register(new ToolDefinition(
                "notification.request_unsubscribe_verification",
                IntentType.NOTIFICATION_REQUEST_UNSUBSCRIBE_VERIFICATION,
                "Send an email verification code before hard-unsubscribing a subscriber.",
                RiskLevel.RISKY_WRITE,
                true,
                Set.of("subscriberId"),
                Set.of("reason")
        ));
        register(new ToolDefinition(
                "notification.unsubscribe_subscriber",
                IntentType.NOTIFICATION_UNSUBSCRIBE,
                "Hard-unsubscribe a subscriber after email-OTP verification. Requires verificationId and 6-digit verificationCode.",
                RiskLevel.DESTRUCTIVE,
                true,
                Set.of("subscriberId", "verificationId", "verificationCode"),
                Set.of()
        ));

        log.info("ToolRegistry initialized with {} tools", byName.size());
    }

    private void register(ToolDefinition def) {
        if (byName.put(def.name(), def) != null) {
            throw new IllegalStateException("Duplicate tool name: " + def.name());
        }
        if (byIntent.put(def.intent(), def) != null) {
            throw new IllegalStateException("Duplicate intent: " + def.intent());
        }
    }

    public Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Optional<ToolDefinition> findByIntent(IntentType intent) {
        return Optional.ofNullable(byIntent.get(intent));
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    /** Immutable view ordered by registration order, suitable for prompt rendering. */
    public Map<String, ToolDefinition> all() {
        return Map.copyOf(byName);
    }
}
