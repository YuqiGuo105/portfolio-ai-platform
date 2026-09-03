package site.yuqi.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import site.yuqi.mcp.registry.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the gateway context loads with the tool-catalog YAML on the
 * classpath and all adapters wired.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "domain.portfolio.base-url=http://localhost:9991",
        "domain.admin.base-url=http://localhost:9992",
        "domain.notification.base-url=http://localhost:9993",
        "mcp.internal-token="
})
class McpGatewayApplicationTests {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void contextLoads() {
        // passing == @SpringBootApplication wired up
    }

    @Test
    void contactToolRoutesToPublicPortfolioApi() {
        var tool = toolRegistry.find("contact.email_owner").orElseThrow();

        assertThat(tool.isConfirmRequired()).isTrue();
        assertThat(tool.getEndpoint().getTarget()).isEqualTo("portfolio");
        assertThat(tool.getEndpoint().getPath()).isEqualTo("/api/contact");
    }

    @Test
    void outboxToolMatchesAdminServiceContract() {
        var tool = toolRegistry.find("admin.list_outbox_events").orElseThrow();

        assertThat(tool.getEndpoint().getTarget()).isEqualTo("admin");
        assertThat(tool.getEndpoint().getMethod()).isEqualTo("GET");
        assertThat(tool.getEndpoint().getPath()).isEqualTo("/api/admin/outbox-events");
    }

    @Test
    void publicationVerificationToolsMatchDownstreamContracts() {
        var timeline = toolRegistry.find("admin.get_operation_timeline").orElseThrow();
        var delivery = toolRegistry.find("notification.get_publication_delivery").orElseThrow();

        assertThat(timeline.getEndpoint().getPath()).isEqualTo("/api/admin/operations/timeline");
        assertThat(delivery.getEndpoint().getPath())
                .isEqualTo("/api/admin/notifications/publication-delivery");
    }

    @Test
    void coverUploadToolIsConfirmedEditorWriteWithBoundedPayload() {
        var tool = toolRegistry.find("admin.upload_content_cover").orElseThrow();

        assertThat(tool.isConfirmRequired()).isTrue();
        assertThat(tool.getRequiredRole()).isEqualTo("EDITOR");
        assertThat(tool.getEndpoint().getTarget()).isEqualTo("admin");
        assertThat(tool.getEndpoint().getPath())
                .isEqualTo("/api/admin/content/{sourceType}/{sourceId}/cover");
        assertThat(tool.getParameters()).filteredOn(parameter -> "imageBase64".equals(parameter.getName()))
                .singleElement().extracting("maxLength").isEqualTo(7_100_000);
    }

    @Test
    void governanceOperationsAreExposedThroughCanonicalCatalog() {
        assertThat(toolRegistry.all()).extracting("name").contains(
                "publication.publish", "audit.search", "audit.get_change_diff",
                "content.list_versions", "content.diff_versions", "content.rollback",
                "visitor.segment_preview", "visitor.rule_test", "visitor.explain_match",
                "rules.list_templates", "rules.create_from_template",
                "career.get_profile", "career.update_memory", "resume.activate_version",
                "platform.health_summary", "platform.run_diagnostics",
                "cost.get_summary", "cost.set_budget", "cost.explain_spike",
                "subscription.create", "subscription.list", "subscription.delete");

        var publish = toolRegistry.find("publication.publish").orElseThrow();
        assertThat(publish.isConfirmRequired()).isTrue();
        assertThat(publish.getParameters()).extracting("name")
                .contains("notifySubscribers", "audience");
    }
}
