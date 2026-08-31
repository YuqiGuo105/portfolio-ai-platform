package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CareerServiceAdapterTest {
    @Test
    void forwardsInternalTokenUsingDomainContractHeader() {
        CareerServiceAdapter adapter = new CareerServiceAdapter(WebClient.builder());
        ReflectionTestUtils.setField(adapter, "internalToken", "career-token");
        WebClient.RequestHeadersSpec<?> request = mock(WebClient.RequestHeadersSpec.class);
        adapter.decorate(request, Map.of());
        verify(request).header("X-Internal-Token", "career-token");
    }
}
