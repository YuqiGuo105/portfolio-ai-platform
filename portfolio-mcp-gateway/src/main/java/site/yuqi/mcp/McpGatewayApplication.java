package site.yuqi.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Portfolio MCP Gateway entry point.
 *
 * <p>Loads {@code tool-catalog.yaml} at startup, exposes
 * {@code POST /api/tools/{name}/invoke}, validates parameters + risk gates,
 * applies idempotency on writes, audits everything, and forwards into the
 * appropriate domain service.
 */
@SpringBootApplication
public class McpGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpGatewayApplication.class, args);
    }
}
