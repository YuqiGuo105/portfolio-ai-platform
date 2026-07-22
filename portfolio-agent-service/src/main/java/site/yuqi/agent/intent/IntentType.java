package site.yuqi.agent.intent;

/**
 * Canonical intent labels. The LLM must return exactly one of these as
 * {@code intent}; anything else is rejected by {@link IntentValidator}.
 *
 * <p>Special values:
 * <ul>
 *   <li>{@link #UNKNOWN}              – out of scope for the platform.</li>
 *   <li>{@link #CLARIFICATION_NEEDED} – classifier knows the candidate tool
 *       but a required entity is missing or confidence is too low.</li>
 *   <li>{@link #GENERAL_CHAT}         – no tool needed, just a chat reply.</li>
 *   <li>{@link #KNOWLEDGE_QA}         – RAG retrieval; not a write path.</li>
 * </ul>
 */
public enum IntentType {
    UNKNOWN,
    CLARIFICATION_NEEDED,
    GENERAL_CHAT,

    KNOWLEDGE_QA,
    WEB_GUIDE,
    HANDOFF_REQUESTED,

    PENDING_ACTION_CONFIRM,
    PENDING_ACTION_CANCEL,
    PENDING_ACTION_CLARIFY,

    CONTACT_EMAIL_OWNER,

    ADMIN_SEARCH_CONTENT,
    ADMIN_GET_CONTENT,
    ADMIN_CREATE_DRAFT,
    ADMIN_UPDATE_CONTENT,
    ADMIN_PUBLISH_CONTENT,
    ADMIN_REINDEX_RAG,
    ADMIN_REINDEX_SEARCH,
    ADMIN_LIST_INDEXING_JOBS,
    ADMIN_RETRY_INDEXING_JOB,
    ADMIN_LIST_OUTBOX_EVENTS,

    ANALYTICS_GET_VISITOR_SUMMARY,
    ANALYTICS_GET_TOP_PAGES,
    ANALYTICS_GET_REFERRER_SUMMARY,

    ALERTS_LIST_RULES,
    ALERTS_GET_RULE,
    ALERTS_PREPARE_CHANGE,
    ALERTS_APPLY_CHANGE,

    NOTIFICATION_GET_STATS,
    NOTIFICATION_LIST_SUBSCRIBERS,
    NOTIFICATION_GET_SUBSCRIBER,
    NOTIFICATION_LIST_NOTIFICATIONS,
    NOTIFICATION_LIST_FAILED_DELIVERIES,
    NOTIFICATION_RETRY_FAILED_DELIVERY,
    NOTIFICATION_SEND_TEST,
    NOTIFICATION_UPDATE_SUBSCRIPTION,

    SUBSCRIPTION_REQUEST_UNSUBSCRIBE_CODE,
    SUBSCRIPTION_CONFIRM_UNSUBSCRIBE
}
