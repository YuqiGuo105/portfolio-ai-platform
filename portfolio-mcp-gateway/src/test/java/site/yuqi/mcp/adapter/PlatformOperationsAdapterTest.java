package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.model.ToolDefinition;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformOperationsAdapterTest {
    @Test
    void diagnosticsAuthenticatesProtectedCareerAndAgentProbes() {
        AtomicBoolean careerInternal = new AtomicBoolean();
        AtomicBoolean agentInternal = new AtomicBoolean();
        ExchangeFunction exchange = request -> {
            String internalToken = request.headers().getFirst("X-Internal-Token");
            careerInternal.compareAndSet(false, "career-secret".equals(internalToken));
            agentInternal.compareAndSet(false, "agent-secret".equals(internalToken));
            return Mono.just(org.springframework.web.reactive.function.client.ClientResponse
                    .create(org.springframework.http.HttpStatus.OK).build());
        };
        CloudRunIdentityTokenProvider tokenProvider = mock(CloudRunIdentityTokenProvider.class);
        when(tokenProvider.tokenFor("https://career.test")).thenReturn("oidc-token");
        PlatformOperationsAdapter adapter = new PlatformOperationsAdapter(
                WebClient.builder().exchangeFunction(exchange), tokenProvider);
        set(adapter, "portfolioUrl", "https://portfolio.test");
        set(adapter, "notificationUrl", "https://notification.test");
        set(adapter, "adminUrl", "https://admin.test");
        set(adapter, "alertsUrl", "https://alerts.test");
        set(adapter, "careerUrl", "https://career.test");
        set(adapter, "careerInternalToken", "career-secret");
        set(adapter, "careerCloudRunIdTokenEnabled", true);
        set(adapter, "agentUrl", "https://agent.test");
        set(adapter, "agentInternalToken", "agent-secret");
        ToolDefinition tool = mock(ToolDefinition.class);
        when(tool.getName()).thenReturn("platform.run_diagnostics");

        Map<String, Object> result = adapter.invoke(tool, Map.of());

        assertThat(result).containsEntry("status", "UP").containsEntry("healthy", 6L);
        org.mockito.Mockito.verify(tokenProvider).tokenFor("https://career.test");
        assertThat(careerInternal).isTrue();
        assertThat(agentInternal).isTrue();
    }

    private static void set(Object target, String field, Object value) {
        ReflectionTestUtils.setField(target, field, value);
    }
}
