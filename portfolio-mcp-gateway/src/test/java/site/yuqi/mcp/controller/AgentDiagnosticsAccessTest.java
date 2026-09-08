package site.yuqi.mcp.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.adapter.AdapterResolver;
import site.yuqi.mcp.adapter.AgentServiceAdapter;
import site.yuqi.mcp.audit.AuditService;
import site.yuqi.mcp.idempotency.IdempotencyKeyService;
import site.yuqi.mcp.model.ToolMode;
import site.yuqi.mcp.registry.ToolRegistry;
import site.yuqi.mcp.security.AnalyticsPrivacyPolicy;
import site.yuqi.mcp.security.RiskGateValidator;
import site.yuqi.mcp.validation.ParameterValidator;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentDiagnosticsAccessTest {
    @Test void canonicalToolsRequireAdminAndSendAgentCompatibleCredentials() {
        var registry = new ToolRegistry(new DefaultResourceLoader());
        ReflectionTestUtils.setField(registry, "catalogLocation", "classpath:tool-catalog.yaml");
        ReflectionTestUtils.invokeMethod(registry, "load");
        var resolver = mock(AdapterResolver.class);
        var calls = new AtomicInteger();
        var runId = UUID.randomUUID().toString();
        var adapter = new AgentServiceAdapter(WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer agent-test-token");
            assertThat(request.url().getPath()).isIn("/api/admin/conversations", "/api/admin/conversations/runs/" + runId);
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"found\":true,\"events\":[]}").build());
        }));
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://agent.test");
        ReflectionTestUtils.setField(adapter, "internalToken", "agent-test-token");
        when(resolver.find("agent")).thenReturn(Optional.of(adapter));
        var controller = new ToolController(registry, new ParameterValidator(), new AnalyticsPrivacyPolicy(),
                new RiskGateValidator(), mock(IdempotencyKeyService.class), resolver, mock(AuditService.class));
        ReflectionTestUtils.setField(controller, "internalToken", "gateway-test-token");
        for (String name : new String[]{"agent.search_runs", "agent.get_run_diagnostics"}) {
            var tool = registry.find(name).orElseThrow();
            assertThat(tool.getRequiredRole()).isEqualTo("ADMIN");
            assertThat(tool.getMode()).isEqualTo(ToolMode.READ);
            var args = name.endsWith("diagnostics") ? Map.<String, Object>of("runId", runId) : Map.<String, Object>of("q", "travel");
            assertThat(controller.invoke(name, args, null, null, "forged", "ADMIN", null, null)
                    .getStatusCode().value()).isEqualTo(401);
            for (String role : new String[]{null, "VIEWER", "EDITOR", "PUBLISHER"}) {
                assertThat(controller.invoke(name, args, "Bearer gateway-test-token", null, "user", role, null, null)
                        .getStatusCode().value()).isEqualTo(403);
            }
            assertThat(controller.invoke(name, args, "Bearer gateway-test-token", null, "admin", "ADMIN", null, null)
                    .getStatusCode().value()).isEqualTo(200);
        }
        assertThat(calls.get()).isEqualTo(2);
    }
}
