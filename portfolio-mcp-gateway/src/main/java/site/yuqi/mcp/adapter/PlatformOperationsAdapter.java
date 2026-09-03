package site.yuqi.mcp.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import site.yuqi.mcp.model.ToolDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class PlatformOperationsAdapter implements DomainServiceAdapter {
    private final WebClient.Builder builder;
    private final CloudRunIdentityTokenProvider identityTokenProvider;

    @Value("${domain.portfolio.base-url}") private String portfolioUrl;
    @Value("${domain.admin.base-url}") private String adminUrl;
    @Value("${domain.notification.base-url}") private String notificationUrl;
    @Value("${domain.alerts.base-url}") private String alertsUrl;
    @Value("${domain.career.base-url}") private String careerUrl;
    @Value("${domain.career.internal-token:}") private String careerInternalToken;
    @Value("${domain.career.cloud-run-id-token-enabled:false}") private boolean careerCloudRunIdTokenEnabled;
    @Value("${domain.agent.base-url}") private String agentUrl;
    @Value("${domain.agent.internal-token:}") private String agentInternalToken;

    public PlatformOperationsAdapter(
            WebClient.Builder builder,
            CloudRunIdentityTokenProvider identityTokenProvider) {
        this.builder = builder;
        this.identityTokenProvider = identityTokenProvider;
    }
    @Override public String target() { return "platform"; }

    @Override
    public Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args) {
        List<ProbeSpec> specs = List.of(
                new ProbeSpec("api", portfolioUrl, "/api/health", null, false),
                new ProbeSpec("database_kafka_email", notificationUrl, "/actuator/health", null, false),
                new ProbeSpec("search_rag", adminUrl, "/actuator/health", null, false),
                new ProbeSpec("visitor_rules", alertsUrl, "/actuator/health", null, false),
                new ProbeSpec("resume_vault", careerUrl, "/actuator/health",
                        careerInternalToken, careerCloudRunIdTokenEnabled),
                new ProbeSpec("redis_agent", agentUrl, "/actuator/health", agentInternalToken, false));
        List<CompletableFuture<Probe>> futures = specs.stream()
                .map(spec -> CompletableFuture.supplyAsync(() -> probe(spec))).toList();
        List<Probe> probes = futures.stream().map(CompletableFuture::join).toList();
        long healthy = probes.stream().filter(p -> "UP".equals(p.status())).count();
        String status = healthy == probes.size() ? "UP" : healthy == 0 ? "DOWN" : "DEGRADED";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("healthy", healthy);
        result.put("total", probes.size());
        result.put("checkedAt", Instant.now());
        if ("platform.run_diagnostics".equals(tool.getName())) {
            result.put("components", probes);
        } else {
            result.put("components", probes.stream().collect(java.util.stream.Collectors.toMap(
                    Probe::name, Probe::status, (a, b) -> a, LinkedHashMap::new)));
        }
        return result;
    }

    private Probe probe(ProbeSpec spec) {
        long start = System.nanoTime();
        try {
            RequestHeadersSpec<?> request = builder.baseUrl(spec.baseUrl()).build().get().uri(spec.path());
            if (spec.internalToken() != null && !spec.internalToken().isBlank()) {
                request = request.header("X-Internal-Token", spec.internalToken());
            }
            if (spec.cloudRunIdToken()) {
                request = request.headers(
                        headers -> headers.setBearerAuth(identityTokenProvider.tokenFor(spec.baseUrl())));
            }
            Integer code = request.retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            reactor.core.publisher.Mono.error(new IllegalStateException("HTTP " + response.statusCode().value())))
                    .toBodilessEntity().map(response -> response.getStatusCode().value())
                    .timeout(Duration.ofSeconds(5)).block();
            return new Probe(spec.name(), "UP", code == null ? 200 : code, elapsed(start), null);
        } catch (Exception e) {
            return new Probe(spec.name(), "DOWN", 0, elapsed(start),
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000L; }
    private record ProbeSpec(
            String name,
            String baseUrl,
            String path,
            String internalToken,
            boolean cloudRunIdToken) {}
    public record Probe(String name, String status, int httpStatus, long latencyMs, String error) {}
}
