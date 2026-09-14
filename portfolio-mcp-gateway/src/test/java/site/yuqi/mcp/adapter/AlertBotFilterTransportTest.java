package site.yuqi.mcp.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class AlertBotFilterTransportTest {
    @Test
    void forwardsNestedFilterAndTrustedServiceHeaderWithoutConfirmationFlags() throws Exception {
        var captured = new AtomicReference<String>();
        var header = new AtomicReference<String>();
        var builder = WebClient.builder().exchangeFunction(request -> {
            var output = new MockClientHttpRequest(request.method(), request.url());
            header.set(request.headers().getFirst("X-Internal-Token"));
            return request.writeTo(output, ExchangeStrategies.withDefaults())
                    .then(reactor.core.publisher.Mono.defer(output::getBodyAsString)).map(body -> {
                        captured.set(body);
                        return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                                .body("{\"changeId\":\"test-change\"}").build();
                    });
        });
        var adapter = new AlertsServiceAdapter(builder);
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://alerts.example.test");
        ReflectionTestUtils.setField(adapter, "timeoutMs", 15000);
        ReflectionTestUtils.setField(adapter, "internalToken", "test-only-token");
        var patch = Map.of("filters", Map.of("bot", "EXCLUDE"));
        var tool = ToolDefinition.builder().name("alerts.prepare_change").mode(ToolMode.WRITE)
                .endpoint(new ToolDefinition.Endpoint("alerts", "POST", "/api/alert-rules/changes/prepare")).build();
        adapter.invoke(tool, Map.of("action", "UPDATE", "ruleId", 1, "patch", patch,
                "reason", "Exclude detected bots", "_idempotencyKey", "stable-filter-key", "_confirmed", true));
        assertThat(header.get()).isEqualTo("test-only-token");
        var body = new ObjectMapper().readTree(captured.get());
        assertThat(body.at("/patch/filters/bot").asText()).isEqualTo("EXCLUDE");
        assertThat(body.has("_confirmed")).isFalse();
        assertThat(body.has("_idempotencyKey")).isFalse();
    }
}
