package site.yuqi.mcp.adapter;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.mcp.model.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @SuppressWarnings("unchecked")
    void wrapsCreateDraftInAdminMutationEnvelope() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ToolDefinition tool = new ToolDefinition();
        tool.setName("admin.create_content_draft");
        Map<String, Object> args = new HashMap<>();
        args.put("sourceType", "PROJECT");
        args.put("title", "Open Source Reliability Engineering");
        args.put("body", "<p>Evidence</p>");
        args.put("tags", List.of("Java", "Open Source"));
        args.put("featured", true);
        args.put("num", 95);

        adapter.prepareArgs(tool, args);

        assertEquals("PROJECT", args.get("sourceType"));
        assertEquals(false, args.get("publish"));
        Map<String, Object> data = (Map<String, Object>) args.get("data");
        assertEquals("Open Source Reliability Engineering", data.get("title"));
        assertEquals("<p>Evidence</p>", data.get("content"));
        assertEquals(List.of("Java", "Open Source"), data.get("tags"));
        assertEquals(true, data.get("featured"));
        assertEquals(95, data.get("num"));
        assertFalse(data.containsKey("body"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void wrapsUpdateAndPreservesPathAndChangeNoteFields() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ToolDefinition tool = new ToolDefinition();
        tool.setName("admin.update_content");
        Map<String, Object> args = new HashMap<>();
        args.put("sourceType", "LIFE");
        args.put("sourceId", "5d164020-7ef5-47d4-bafa-4eef4604b58f");
        args.put("summary", "Updated summary");
        args.put("changeNote", "MCP edit");

        adapter.prepareArgs(tool, args);

        assertEquals("LIFE_BLOG", args.get("sourceType"));
        assertEquals("5d164020-7ef5-47d4-bafa-4eef4604b58f", args.get("sourceId"));
        assertEquals("MCP edit", args.get("changeNote"));
        assertEquals("Updated summary", ((Map<String, Object>) args.get("data")).get("summary"));
    }
}
