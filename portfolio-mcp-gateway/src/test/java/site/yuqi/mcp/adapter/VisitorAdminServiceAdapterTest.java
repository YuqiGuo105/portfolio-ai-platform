package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class VisitorAdminServiceAdapterTest {
    private ToolDefinition tool() {
        return ToolDefinition.builder().name("visitor.search_events").requiredRole("ADMIN").mode(ToolMode.READ)
                .endpoint(new ToolDefinition.Endpoint("visitor-admin", "GET", "/api/admin/visitors")).build();
    }

    private VisitorAdminServiceAdapter adapter(WebClient.Builder builder) {
        var adapter = new VisitorAdminServiceAdapter(builder);
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://analytics.test");
        ReflectionTestUtils.setField(adapter, "internalToken", "test-key");
        ReflectionTestUtils.setField(adapter, "timeoutMs", 2000);
        return adapter;
    }

    @Test void encodesQueryOnceAndNeverForwardsRoleOrNestedObjects() {
        var adapter = adapter(WebClient.builder().exchangeFunction(request -> {
            assertThat(request.headers().getFirst("X-Internal-Token")).isEqualTo("test-key");
            assertThat(request.headers().getFirst("Cache-Control")).isEqualTo("no-store");
            var query = new HashMap<String, String>();
            for (String part : request.url().getRawQuery().split("&")) {
                var pair = part.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertThat(query).containsEntry("path", "/life-blog/2?a=1&b=hello+world")
                    .containsEntry("city", "Salt Lake City").containsEntry("page", "2")
                    .containsEntry("size", "50").containsEntry("includeAdmin", "false")
                    .doesNotContainKeys("_mcpRole", "filter", "window");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"items\":[],\"page\":{\"totalElements\":0}}").build());
        }));
        var response = adapter.invoke(tool(), Map.of("_mcpRole", "ADMIN",
                "filter", Map.of("city", "Salt Lake City", "path", "/life-blog/2?a=1&b=hello+world"),
                "page", Map.of("number", 2, "size", 50)));
        assertThat(response).containsKey("items").doesNotContainKey("privacyApplied");
    }

    @Test void directAdapterRejectsMissingRoleAndUnexpectedEndpoint() {
        var count = new AtomicInteger();
        var adapter = adapter(WebClient.builder().exchangeFunction(request -> {
            count.incrementAndGet();
            return Mono.error(new AssertionError("Must not dispatch"));
        }));
        assertThatThrownBy(() -> adapter.invoke(tool(), Map.of()))
                .isInstanceOf(AdapterException.class).hasMessageContaining("Administrator");
        var unexpected = tool();
        unexpected.setEndpoint(new ToolDefinition.Endpoint("visitor-admin", "GET", "/api/admin/other"));
        assertThatThrownBy(() -> adapter.invoke(unexpected, Map.of("_mcpRole", "ADMIN")))
                .isInstanceOf(AdapterException.class);
        assertThat(count.get()).isZero();
    }

    @Test void retryableReadRetriesOnceAndDoesNotExposeDownstreamErrorBody() {
        var count = new AtomicInteger();
        var adapter = adapter(WebClient.builder().exchangeFunction(request -> {
            count.incrementAndGet();
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("private-ip-session-and-secret-data").build());
        }));
        assertThatThrownBy(() -> adapter.invoke(tool(), Map.of("_mcpRole", "ADMIN")))
                .isInstanceOf(AdapterException.class).hasMessage("Downstream returned HTTP 503");
        assertThat(count.get()).isEqualTo(2);
    }
}
