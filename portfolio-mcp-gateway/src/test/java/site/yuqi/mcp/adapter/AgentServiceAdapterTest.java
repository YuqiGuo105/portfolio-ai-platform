package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;
import java.util.HashMap;
import site.yuqi.mcp.model.ToolDefinition;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentServiceAdapterTest {
    @Test void rejectsUntrustedPathArguments() {
        var adapter = new AgentServiceAdapter(WebClient.builder());
        var tool = mock(ToolDefinition.class);
        org.mockito.Mockito.when(tool.getName()).thenReturn("agent.get_run_diagnostics");
        assertThatThrownBy(() -> adapter.prepareArgs(tool, new HashMap<>(Map.of("runId", "../other"))))
                .isInstanceOf(AdapterException.class);
        assertThatThrownBy(() -> adapter.prepareArgs(tool, new HashMap<>()))
                .isInstanceOf(AdapterException.class);
    }

    @Test void forwardsOnlyWithConfiguredInternalCredential() {
        var adapter = new AgentServiceAdapter(WebClient.builder());
        var request = mock(WebClient.RequestHeadersSpec.class);
        assertThatThrownBy(() -> adapter.decorate(request, Map.of())).isInstanceOf(AdapterException.class);
        ReflectionTestUtils.setField(adapter, "internalToken", "test-token");
        adapter.decorate(request, Map.of());
        verify(request).header("Authorization", "Bearer test-token");
    }
}
