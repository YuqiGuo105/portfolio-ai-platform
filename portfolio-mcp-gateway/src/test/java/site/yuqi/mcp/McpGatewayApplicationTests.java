package site.yuqi.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

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

    @Test
    void contextLoads() {
        // passing == @SpringBootApplication wired up
    }
}
