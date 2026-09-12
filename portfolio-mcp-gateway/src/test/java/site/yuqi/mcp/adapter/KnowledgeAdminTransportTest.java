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

class KnowledgeAdminTransportTest {
    @Test void forwardsNestedKnowledgeWithoutFlatteningOrInternalFlags() throws Exception {
        var captured = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        var url = new AtomicReference<String>();
        var builder = WebClient.builder().exchangeFunction(request -> {
            var output = new MockClientHttpRequest(request.method(), request.url());
            key.set(request.headers().getFirst("Idempotency-Key"));
            url.set(request.url().getPath());
            return request.writeTo(output, ExchangeStrategies.withDefaults())
                    .then(reactor.core.publisher.Mono.defer(output::getBodyAsString)).map(body -> {
                captured.set(body);
                return ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body("{\"indexing\":{\"status\":\"PENDING\"}}").build();
            });
        });
        var adapter = new AdminServiceAdapter(builder);
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://admin.example.test");
        ReflectionTestUtils.setField(adapter, "timeoutMs", 15000);
        ReflectionTestUtils.setField(adapter, "adminSecret", "test-only-service-key");
        var document = Map.of("title", "Example knowledge", "content", "Verified answer");
        var policy = Map.of("status", "ACTIVE", "answerVisibility", "public");
        var preconditions = Map.of("revision", "a".repeat(32));
        var tool = ToolDefinition.builder().name("knowledge.update").mode(ToolMode.WRITE)
                .endpoint(new ToolDefinition.Endpoint("admin", "PUT", "/api/admin/knowledge/{id}")).build();
        var result = adapter.invoke(tool, Map.of("id", "record-id", "document", document, "policy", policy,
                "preconditions", preconditions, "_idempotencyKey", "stable-test-key", "_confirmed", true));
        assertThat(url.get()).isEqualTo("/api/admin/knowledge/record-id");
        assertThat(key.get()).isEqualTo("stable-test-key");
        assertThat(new ObjectMapper().readValue(captured.get(), Map.class))
                .isEqualTo(Map.of("document", document, "policy", policy, "preconditions", preconditions));
        assertThat(result).containsEntry("indexing", Map.of("status", "PENDING"));
    }
}
