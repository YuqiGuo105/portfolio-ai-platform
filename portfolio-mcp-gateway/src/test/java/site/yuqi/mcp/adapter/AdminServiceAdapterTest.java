package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.mcp.model.ToolDefinition;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminServiceAdapterTest {

    @Test
    void forwardsConfiguredServiceCredential() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ReflectionTestUtils.setField(adapter, "adminSecret", "admin-service-secret");
        WebClient.RequestHeadersSpec<?> request = mock(WebClient.RequestHeadersSpec.class);

        adapter.decorate(request, Map.of());

        verify(request).header("X-Admin-Secret", "admin-service-secret");
    }

    @Test
    void rejectsCallsWhenServiceCredentialIsMissing() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ReflectionTestUtils.setField(adapter, "adminSecret", " ");
        WebClient.RequestHeadersSpec<?> request = mock(WebClient.RequestHeadersSpec.class);

        AdapterException error = assertThrows(
                AdapterException.class,
                () -> adapter.decorate(request, Map.of()));

        assertTrue(error.getMessage().contains("credential is not configured"));
    }

    @Test
    void mapsCatalogSourceTypeToAdminApiType() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ToolDefinition tool = new ToolDefinition();
        tool.setName("admin.search_content");
        Map<String, Object> args = new HashMap<>(Map.of(
                "sourceType", "BLOG",
                "keyword", "Git"));

        adapter.prepareArgs(tool, args);

        assertTrue(!args.containsKey("sourceType"));
        assertTrue("BLOG".equals(args.get("type")));
    }
}
