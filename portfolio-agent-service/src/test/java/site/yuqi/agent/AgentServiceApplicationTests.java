package site.yuqi.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import site.yuqi.agent.observability.OutboxRepository;

/**
 * Verifies the Spring context loads with default profile. All external
 * credentials are intentionally left blank — wiring only.
 * DataSource / Flyway are excluded; JdbcTemplate and OutboxRepository are
 * mocked so CI runs without a real Postgres.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "mcp-gateway.base-url=http://localhost:9999",
        "agent.model.openai.api-key=",
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "spring.flyway.enabled=false"
})
class AgentServiceApplicationTests {

    @MockBean
    JdbcTemplate jdbcTemplate;

    @MockBean
    OutboxRepository outboxRepository;

    @Test
    void contextLoads() {
        // passing == @SpringBootApplication wired up
    }
}

