package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PortfolioApiAdapterTest {
    @Test void actualContactRequestCarriesCredentialAndOperationHeadersWithoutRetryingWrites() {
        var calls = new AtomicInteger();
        String secret = "a-dedicated-test-credential-with-32-characters";
        var builder = WebClient.builder().exchangeFunction(request -> {
            calls.incrementAndGet();
            assertThat(request.url().toString()).isEqualTo("https://www.yuqi.site/api/contact");
            assertThat(request.headers().getFirst("X-Portfolio-Contact-Token")).isEqualTo(secret);
            assertThat(request.headers().getFirst("Idempotency-Key")).isEqualTo("operation-key");
            assertThat(request.headers().getFirst("X-Operation-Id")).isEqualTo("operation-id");
            return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).body("unavailable").build());
        });
        var adapter = new PortfolioApiAdapter(builder);
        ReflectionTestUtils.setField(adapter, "contactToken", secret);
        ReflectionTestUtils.setField(adapter, "baseUrl", "https://www.yuqi.site");
        ReflectionTestUtils.setField(adapter, "timeoutMs", 15000);
        var tool = ToolDefinition.builder().name("contact.email_owner").mode(ToolMode.WRITE)
                .endpoint(ToolDefinition.Endpoint.builder().method("POST").path("/api/contact").build()).build();
        assertThatThrownBy(() -> adapter.invoke(tool, Map.of("email", "fixture@example.com", "message", "Test",
                "_idempotencyKey", "operation-key", "_operationId", "operation-id")))
                .isInstanceOfSatisfying(AdapterException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(503));
        assertThat(calls.get()).isEqualTo(1);
    }

    private ToolDefinition tool(String name) {
        var tool = mock(ToolDefinition.class);
        when(tool.getName()).thenReturn(name);
        return tool;
    }

    @Test void contactRequiresDedicatedServerCredential() {
        var adapter = new PortfolioApiAdapter(WebClient.builder());
        var request = mock(WebClient.RequestHeadersSpec.class);
        var tool = tool("contact.email_owner");
        assertThatThrownBy(() -> adapter.decorate(request, Map.of(), Map.of(), tool))
                .isInstanceOf(AdapterException.class);
        ReflectionTestUtils.setField(adapter, "contactToken", "too-short");
        assertThatThrownBy(() -> adapter.decorate(request, Map.of(), Map.of(), tool))
                .isInstanceOf(AdapterException.class);
        verifyNoInteractions(request);
    }

    @Test void callerArgumentsCannotChooseTheServiceCredential() {
        var adapter = new PortfolioApiAdapter(WebClient.builder());
        String secret = "a-dedicated-test-credential-with-32-characters";
        ReflectionTestUtils.setField(adapter, "contactToken", secret);
        var request = mock(WebClient.RequestHeadersSpec.class);
        adapter.decorate(request, Map.of("source", "mcp", "contactToken", "forged"),
                Map.of("_contactToken", "forged"), tool("contact.email_owner"));
        verify(request).header("X-Portfolio-Contact-Token", secret);
        verifyNoMoreInteractions(request);
    }

    @Test void publicReadsDoNotCarryContactCredentials() {
        var adapter = new PortfolioApiAdapter(WebClient.builder());
        ReflectionTestUtils.setField(adapter, "contactToken", "a-dedicated-test-credential-with-32-characters");
        var request = mock(WebClient.RequestHeadersSpec.class);
        adapter.decorate(request, Map.of(), Map.of(), tool("portfolio.search_public_knowledge"));
        verifyNoInteractions(request);
    }
}
