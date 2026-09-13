package site.yuqi.mcp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.adapter.*;
import site.yuqi.mcp.audit.AuditService;
import site.yuqi.mcp.idempotency.IdempotencyKeyService;
import site.yuqi.mcp.registry.ToolRegistry;
import site.yuqi.mcp.security.AnalyticsPrivacyPolicy;
import site.yuqi.mcp.security.RiskGateValidator;
import site.yuqi.mcp.validation.ParameterValidator;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VisitorDetailsAccessTest {
    private final AtomicInteger privateCalls = new AtomicInteger();
    private final AtomicInteger publicCalls = new AtomicInteger();
    private final IdempotencyKeyService ledger = mock(IdempotencyKeyService.class);
    private ToolController controller;
    private VisitorAdminServiceAdapter privateAdapter;

    @BeforeEach void setup() {
        var registry = new ToolRegistry(new DefaultResourceLoader());
        ReflectionTestUtils.setField(registry, "catalogLocation", "classpath:tool-catalog.yaml");
        ReflectionTestUtils.invokeMethod(registry, "load");
        privateAdapter = new VisitorAdminServiceAdapter(WebClient.builder().exchangeFunction(request -> {
            privateCalls.incrementAndGet();
            assertThat(request.headers().getFirst("X-Internal-Token")).isEqualTo("visitor-service-test-key");
            assertThat(request.url().getPath()).isEqualTo("/api/admin/visitors");
            assertThat(request.url().getRawQuery()).doesNotContain("_mcp", "filter=", "window=");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"items\":[{\"eventId\":\"test-event\",\"sessionId\":\"test-session\","
                            + "\"ipAddress\":\"192.0.2.10\",\"userAgent\":\"test-client\",\"city\":\"Example city\"}],"
                            + "\"page\":{\"number\":0,\"size\":25,\"totalElements\":1,\"totalPages\":1}}")
                    .build());
        }));
        configure(privateAdapter);
        ReflectionTestUtils.setField(privateAdapter, "internalToken", "visitor-service-test-key");
        var publicAdapter = new AnalyticsServiceAdapter(WebClient.builder().exchangeFunction(request -> {
            publicCalls.incrementAndGet();
            assertThat(request.url().getPath()).isEqualTo("/api/public/visits/summary");
            assertThat(request.headers().containsKey("X-Internal-Token")).isFalse();
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"ipAddress\":\"192.0.2.10\",\"sessions\":[\"test-session\"],"
                            + "\"countries\":[{\"name\":\"small\",\"count\":1},{\"name\":\"large\",\"count\":20}]}").build());
        }));
        configure(publicAdapter);
        ReflectionTestUtils.setField(publicAdapter, "minBucketCount", 5);
        var resolver = mock(AdapterResolver.class);
        when(resolver.find("visitor-admin")).thenReturn(Optional.of(privateAdapter));
        when(resolver.find("analytics")).thenReturn(Optional.of(publicAdapter));
        var privacy = new AnalyticsPrivacyPolicy();
        ReflectionTestUtils.setField(privacy, "minWindowDays", 7L);
        controller = new ToolController(registry, new ParameterValidator(), privacy,
                new RiskGateValidator(), ledger, resolver, mock(AuditService.class));
        ReflectionTestUtils.setField(controller, "internalToken", "gateway-test-key");
    }

    private void configure(Object adapter) {
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://analytics.test");
        ReflectionTestUtils.setField(adapter, "timeoutMs", 15000);
    }

    @Test void onlyAuthenticatedAdminCanReadUnredactedRecords() throws Exception {
        for (String name : List.of("visitor.search_events", "visitor.get_session_events")) {
            var args = name.endsWith("session_events")
                    ? Map.<String, Object>of("sessionId", "test-session", "_mcpRole", "ADMIN")
                    : Map.<String, Object>of("_mcpRole", "ADMIN");
            assertThat(controller.invoke(name, args, null, null, "forged", "ADMIN", null, null)
                    .getStatusCode().value()).isEqualTo(401);
            for (String role : new String[]{null, "VIEWER", "EDITOR", "PUBLISHER", "unknown"}) {
                assertThat(controller.invoke(name, args, "Bearer gateway-test-key", null, "user", role, null, null)
                        .getStatusCode().value()).isEqualTo(403);
            }
            var response = controller.invoke(name, args, "Bearer gateway-test-key", null, "admin", "ADMIN", null, null);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
            assertThat(new ObjectMapper().writeValueAsString(response.getBody()))
                    .contains("test-event", "test-session", "192.0.2.10", "test-client", "totalElements");
        }
        assertThat(privateCalls.get()).isEqualTo(2);
        verifyNoInteractions(ledger);
    }

    @Test void serverRoleOverridesSuppliedControlArguments() {
        var response = controller.invoke("visitor.search_events", Map.of("_mcpRole", "VIEWER"),
                "Bearer gateway-test-key", null, "admin", "ADMIN", null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test void invalidNestedQueriesAreRejectedBeforeDispatch() {
        for (var args : List.of(Map.of("page", Map.of("size", 101)),
                Map.of("filter", Map.of("arbitrarySql", "private-value")),
                Map.of("window", Map.of("hours", 745)))) {
            var response = controller.invoke("visitor.search_events", Map.copyOf(args),
                    "Bearer gateway-test-key", null, "admin", "ADMIN", null, null);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().toString()).doesNotContain("private-value");
        }
        assertThat(privateCalls.get()).isZero();
    }

    @Test void missingServiceCredentialFailsClosedAndReadCanBeRetried() {
        ReflectionTestUtils.setField(privateAdapter, "internalToken", " ");
        var response = controller.invoke("visitor.search_events", Map.of(),
                "Bearer gateway-test-key", null, "admin", "ADMIN", null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(((Map<?, ?>) response.getBody()).get("safeToRetry")).isEqualTo(true);
        assertThat(privateCalls.get()).isZero();
    }

    @Test void aggregatePrivacyRemainsUnchangedForViewerAndAdmin() throws Exception {
        var approved = Map.<String, Object>of("_confirmedTimeRange", true,
                "startDate", "2026-09-01", "endDate", "2026-09-07");
        for (String role : List.of("VIEWER", "ADMIN")) {
            var response = controller.invoke("analytics.get_visitor_summary", approved,
                    "Bearer gateway-test-key", null, "user", role, null, null);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(new ObjectMapper().writeValueAsString(response.getBody()))
                    .contains("privacyApplied", "suppressed", "large")
                    .doesNotContain("192.0.2.10", "test-session", "ipAddress", "small");
            var privateQuery = new java.util.HashMap<>(approved);
            privateQuery.put("sessionId", "test-session");
            assertThat(controller.invoke("analytics.get_visitor_summary", privateQuery,
                    "Bearer gateway-test-key", null, "user", role, null, null)
                    .getStatusCode().value()).isEqualTo(403);
        }
        assertThat(privateCalls.get()).isZero();
        assertThat(publicCalls.get()).isEqualTo(2);
    }
}
