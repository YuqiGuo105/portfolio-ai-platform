package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeServiceAdapterTest {
    @Test void internalEvidenceRequiresServiceCredential() {
        var adapter = new KnowledgeServiceAdapter(WebClient.builder());
        var request = mock(WebClient.RequestHeadersSpec.class);
        assertThatThrownBy(() -> adapter.decorate(request, Map.of())).isInstanceOf(AdapterException.class);
        ReflectionTestUtils.setField(adapter, "token", "test-token");
        adapter.decorate(request, Map.of());
        verify(request).header("X-Internal-Token", "test-token");
        assertThat(adapter.target()).isEqualTo("knowledge");
    }
}
