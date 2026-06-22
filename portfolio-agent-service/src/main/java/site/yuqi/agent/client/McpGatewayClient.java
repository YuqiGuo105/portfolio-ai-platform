package site.yuqi.agent.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import site.yuqi.agent.model.ToolInvocation;

import java.time.Duration;
import java.util.Map;

/**
 * Thin client over portfolio-mcp-gateway. Performs:
 *
 * <pre>
 *   POST {base}/api/tools/{name}/invoke
 *   Authorization: Bearer {MCP_GATEWAY_INTERNAL_TOKEN}
 *   Idempotency-Key: {invocation.idempotencyKey?}
 *   Body: invocation.arguments
 * </pre>
 *
 * <p>This is the <strong>only</strong> way the agent reaches a domain
 * service. Direct calls into Portfolio / admin-service / notification-service
 * from the agent are forbidden by Sprint 1 design.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpGatewayClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${mcp-gateway.base-url}")
    private String baseUrl;

    @Value("${mcp-gateway.internal-token:}")
    private String internalToken;

    @Value("${mcp-gateway.timeout-ms:30000}")
    private int timeoutMs;

    @SuppressWarnings("unchecked")
    public Mono<ToolInvocation> invoke(ToolInvocation invocation) {
        long start = System.currentTimeMillis();
        String name = invocation.getName();
        Map<String, Object> args = invocation.getArguments() == null
                ? Map.of()
                : invocation.getArguments();

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();

        return client.post()
                .uri("/api/tools/{name}/invoke", name)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(h -> {
                    if (internalToken != null && !internalToken.isBlank()) {
                        h.setBearerAuth(internalToken);
                    }
                    if (invocation.getIdempotencyKey() != null) {
                        h.add(HttpHeaders.IDEMPOTENCY_KEY, invocation.getIdempotencyKey());
                    }
                })
                .bodyValue(args)
                .retrieve()
                .onStatus(HttpStatusCode::isError, resp ->
                        resp.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .flatMap(body -> Mono.error(new WebClientResponseException(
                                        resp.statusCode().value(),
                                        "MCP gateway error",
                                        resp.headers().asHttpHeaders(),
                                        body.getBytes(),
                                        null))))
                .bodyToMono(Map.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(body -> {
                    invocation.setResult((Map<String, Object>) body);
                    invocation.setSuccess(true);
                    invocation.setLatencyMs(System.currentTimeMillis() - start);
                    return invocation;
                })
                .onErrorResume(err -> {
                    log.warn("MCP gateway invoke failed [{}]: {}", name, err.toString());
                    invocation.setSuccess(false);
                    invocation.setError(err.getMessage());
                    invocation.setLatencyMs(System.currentTimeMillis() - start);
                    return Mono.just(invocation);
                });
    }
}
