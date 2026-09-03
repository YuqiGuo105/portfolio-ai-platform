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
    void forwardsTrustedMcpAuditMetadata() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ReflectionTestUtils.setField(adapter, "adminSecret", "admin-service-secret");
        WebClient.RequestHeadersSpec<?> request = mock(WebClient.RequestHeadersSpec.class);

        adapter.decorate(request, Map.of(), Map.of(
                "_mcpActor", "yuqi", "_mcpTool", "publication.publish",
                "_mcpClient", "codex", "_mcpModel", "gpt-5.6"));

        verify(request).header("X-MCP-Actor", "yuqi");
        verify(request).header("X-MCP-Tool", "publication.publish");
        verify(request).header("X-MCP-Client", "codex");
        verify(request).header("X-MCP-Model", "gpt-5.6");
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
    void mapsTimelineQueryToAdminApiParameter() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ToolDefinition tool = new ToolDefinition();
        tool.setName("admin.get_operation_timeline");
        Map<String, Object> args = new HashMap<>(Map.of(
                "query", "content-123",
                "limit", 100));

        adapter.prepareArgs(tool, args);

        assertEquals("content-123", args.get("q"));
        assertEquals(100, args.get("limit"));
        assertFalse(args.containsKey("query"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrichesPublishResultWithAsynchronousVerificationInstructions() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ToolDefinition tool = new ToolDefinition();
        tool.setName("admin.publish_content");

        Map<String, Object> result = adapter.enrichPublishResult(
                tool,
                Map.of("sourceId", "content-123"),
                Map.of("eventId", "event-456", "status", "PUBLISHED"));

        Map<String, Object> verification = (Map<String, Object>) result.get("verification");
        assertEquals("PENDING_VERIFICATION", verification.get("status"));
        assertEquals("event-456", verification.get("identifier"));
        assertEquals("/admin/operations", verification.get("operationsTimelinePath"));
        assertEquals(
                List.of("notification.get_publication_delivery", "admin.get_operation_timeline"),
                verification.get("tools"));
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

    @Test
    void normalizesCoverUploadSourceTypeWithoutChangingPayload() {
        AdminServiceAdapter adapter = new AdminServiceAdapter(WebClient.builder());
        ToolDefinition tool = new ToolDefinition();
        tool.setName("admin.upload_content_cover");
        Map<String, Object> args = new HashMap<>();
        args.put("sourceType", "LIFE");
        args.put("sourceId", "life-1");
        args.put("imageBase64", "iVBORw0KGgo=");
        args.put("mimeType", "image/png");

        adapter.prepareArgs(tool, args);

        assertEquals("LIFE_BLOG", args.get("sourceType"));
        assertEquals("life-1", args.get("sourceId"));
        assertEquals("iVBORw0KGgo=", args.get("imageBase64"));
        assertEquals("image/png", args.get("mimeType"));
    }
}
