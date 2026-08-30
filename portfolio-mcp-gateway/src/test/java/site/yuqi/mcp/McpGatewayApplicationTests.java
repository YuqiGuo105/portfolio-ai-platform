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
}
