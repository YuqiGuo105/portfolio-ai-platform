package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AlertsServiceAdapterTest {

    @Test
    void forwardsInternalTokenUsingBackendContractHeader() {
        AlertsServiceAdapter adapter = new AlertsServiceAdapter(WebClient.builder());
        ReflectionTestUtils.setField(adapter, "internalToken", "alerts-token");
        WebClient.RequestHeadersSpec<?> request = mock(WebClient.RequestHeadersSpec.class);

        adapter.decorate(request, Map.of());

        verify(request).header("X-Internal-Token", "alerts-token");
    }
}
