package site.yuqi.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies the Spring context loads with default profile. All external
 * credentials are intentionally left blank — wiring only.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "mcp-gateway.base-url=http://localhost:9999",
        "agent.model.openai.api-key="
})
class AgentServiceApplicationTests {

    @Test
    void contextLoads() {
        // passing == @SpringBootApplication wired up
    }
}
