package site.yuqi.mcp.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.mcp.model.*;
import site.yuqi.mcp.security.*;
import site.yuqi.mcp.controller.ToolController;
import site.yuqi.mcp.registry.ToolRegistry;
import site.yuqi.mcp.adapter.AdapterResolver;
import site.yuqi.mcp.idempotency.IdempotencyKeyService;
import site.yuqi.mcp.audit.AuditService;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MediaToolContractTest {
    List<ToolDefinition> tools() throws Exception {
        try (var input = getClass().getResourceAsStream("/tool-catalog.yaml")) {
            return new ObjectMapper(new YAMLFactory()).readValue(input, ToolCatalog.class).getTools().stream()
                    .filter(tool -> tool.getName().startsWith("media.")).toList();
        }
    }

    @Test void catalogHasAdminOnlyToolsWithConfirmedWrites() throws Exception {
        assertThat(tools()).extracting(ToolDefinition::getName).containsExactlyInAnyOrder(
                "media.list", "media.get", "media.get_upload_limits", "media.start_upload", "media.resume_upload",
                "media.complete_upload", "media.update", "media.check_delete", "media.delete", "media.retry_processing",
                "media.get_article", "media.attach_to_article", "media.detach_from_article");
        for (var tool : tools()) {
            assertThat(tool.getRequiredRole()).isEqualTo("ADMIN");
            assertThat(tool.getEndpoint().getTarget()).isEqualTo("admin");
            if (tool.getMode() == ToolMode.WRITE) {
                assertThat(new RiskGateValidator().check(tool, Map.of()).allowed()).isFalse();
                assertThat(new RiskGateValidator().check(tool, Map.of("dryRun", true)).allowed()).isFalse();
                assertThat(new RiskGateValidator().check(tool, Map.of("_confirmed", true)).allowed()).isTrue();
            }
        }
    }

    @Test void validatesIdentifiersVersionsAndBoundedLists() throws Exception {
        var validator = new ParameterValidator();
        var get = tool("media.get");
        assertThat(validator.validate(get, Map.of("id", UUID.randomUUID().toString())).isValid()).isTrue();
        assertThat(validator.validate(get, Map.of("id", "../users?padding=" + "a".repeat(19))).isValid()).isFalse();
        assertThat(validator.validate(get, Map.of("id", UUID.randomUUID().toString(), "url", "http://localhost/")).isValid()).isFalse();
        assertThat(validator.validate(tool("media.list"), Map.of("limit", 101)).isValid()).isFalse();
        assertThat(validator.validate(tool("media.list"), Map.of("kind", "SECRET")).isValid()).isFalse();
        var nullKind = new HashMap<String, Object>();
        nullKind.put("kind", null);
        assertThat(validator.validate(tool("media.list"), nullKind).isValid()).isFalse();
        assertThat(validator.validate(tool("media.delete"), Map.of("id", UUID.randomUUID().toString(), "expectedVersion", -1)).isValid()).isFalse();
        assertThat(validator.validate(tool("media.delete"), Map.of("id", UUID.randomUUID().toString(), "expectedVersion", 0)).isValid()).isTrue();
    }

    @Test void gatewayRejectsNonAdminBeforeDispatchOrWriteAdmission() throws Exception {
        var registry = mock(ToolRegistry.class);
        var resolver = mock(AdapterResolver.class);
        var ledger = mock(IdempotencyKeyService.class);
        var controller = new ToolController(registry, new ParameterValidator(), new AnalyticsPrivacyPolicy(),
                new RiskGateValidator(), ledger, resolver, mock(AuditService.class));
        ReflectionTestUtils.setField(controller, "internalToken", "test-internal-token");
        for (var tool : tools()) {
            when(registry.find(tool.getName())).thenReturn(Optional.of(tool));
            for (String role : List.of("VIEWER", "EDITOR", "PUBLISHER", "", "OWNER"))
                assertThat(controller.invoke(tool.getName(), Map.of(), "Bearer test-internal-token", "test-intent-1",
                        "operator", role, "test", "test").getStatusCode().value()).isEqualTo(403);
        }
        verifyNoInteractions(ledger, resolver);
    }

    private ToolDefinition tool(String name) throws Exception {
        return tools().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }

    @Test void articleToolsRejectUnsafePathsHashesAndUnrelatedSources() throws Exception {
        var validator = new ParameterValidator();
        var args = new HashMap<String, Object>(Map.of("sourceType", "PROJECT", "sourceId", UUID.randomUUID().toString(),
                "id", UUID.randomUUID().toString(), "expectedContentHash", "a".repeat(64), "position", "START"));
        var attach = tool("media.attach_to_article");
        assertThat(validator.validate(attach, args).isValid()).isTrue();
        args.put("sourceType", "LIFE_BLOG");
        assertThat(validator.validate(attach, args).isValid()).isFalse();
        args.put("sourceType", "PROJECT"); args.put("position", "<script>");
        assertThat(validator.validate(attach, args).isValid()).isFalse();
        args.put("position", "END"); args.put("expectedContentHash", "x".repeat(64));
        assertThat(validator.validate(attach, args).isValid()).isFalse();
        args.put("expectedContentHash", "a".repeat(64)); args.put("sourceId", "../".repeat(12));
        assertThat(validator.validate(attach, args).isValid()).isFalse();
    }
}
