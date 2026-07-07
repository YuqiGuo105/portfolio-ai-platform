package site.yuqi.agent.handoff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.agent.observability.EventRecorder;
import site.yuqi.ai.contracts.event.PlatformEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Human Support Handoff Service.
 *
 * Decision flow (from flowchart):
 *   Agent → Can answer safely?
 *     → Yes → Answer user
 *     → Need action → Ask user confirmation
 *     → Low confidence → Handoff
 *     → High risk → Handoff
 *     → User asks human → Handoff
 *   Handoff → CRM (Zendesk / Salesforce / Internal)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HandoffService {

    private final JdbcTemplate jdbc;
    private final WebClient.Builder webClientBuilder;
    private final EventRecorder eventRecorder;

    @Value("${handoff.enabled:true}")
    private boolean enabled;

    @Value("${handoff.crm-webhook-url:}")
    private String crmWebhookUrl;

    @Value("${handoff.crm-api-key:}")
    private String crmApiKey;

    /**
     * Creates a handoff ticket and optionally forwards to external CRM.
     *
     * @return ticket ID
     */
    public UUID createHandoff(UUID conversationId, UUID runId, String userId,
                              HandoffReason reason, String summary) {
        UUID ticketId = UUID.randomUUID();

        // Persist to Aiven PG
        jdbc.update("""
                insert into handoff_ticket (id, conversation_id, run_id, user_id, reason, summary, status, created_at)
                values (?, ?, ?, ?, ?, ?, 'created', now())
                """, ticketId, conversationId, runId, userId, reason.name(), summary);

        // Forward to external CRM if configured
        String externalId = null;
        if (enabled && crmWebhookUrl != null && !crmWebhookUrl.isBlank()) {
            externalId = forwardToCrm(ticketId, conversationId, userId, reason, summary);
            if (externalId != null) {
                jdbc.update("update handoff_ticket set external_ticket_id = ?, status = 'forwarded' where id = ?",
                        externalId, ticketId);
            }
        }

        // Record event for OpenSearch dashboards
        eventRecorder.record(PlatformEvent.now("handoff.created")
                .conversationId(conversationId)
                .runId(runId)
                .userId(userId)
                .service("handoff-service")
                .status("created")
                .payload(Map.of(
                        "ticketId", ticketId.toString(),
                        "reason", reason.name(),
                        "externalTicketId", externalId != null ? externalId : ""
                ))
                .build());

        log.info("Handoff created: ticket={} reason={} conversation={}", ticketId, reason, conversationId);
        return ticketId;
    }

    private String forwardToCrm(UUID ticketId, UUID conversationId, String userId,
                                HandoffReason reason, String summary) {
        try {
            var body = Map.of(
                    "ticketId", ticketId.toString(),
                    "conversationId", conversationId.toString(),
                    "userId", userId != null ? userId : "anonymous",
                    "reason", reason.name(),
                    "summary", summary != null ? summary : "No summary available",
                    "source", "ai-agent"
            );

            var response = webClientBuilder.build()
                    .post().uri(crmWebhookUrl)
                    .header("Authorization", "Bearer " + crmApiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("id")) {
                return response.get("id").toString();
            }
        } catch (Exception e) {
            log.error("CRM webhook failed: {}", e.getMessage());
        }
        return null;
    }
}
