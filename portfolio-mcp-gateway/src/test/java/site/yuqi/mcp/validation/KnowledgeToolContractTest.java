package site.yuqi.mcp.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import site.yuqi.mcp.model.ToolCatalog;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.model.ToolMode;
import site.yuqi.mcp.security.RiskGateValidator;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeToolContractTest {
    List<ToolDefinition> tools() throws Exception {
        try (var input = getClass().getResourceAsStream("/tool-catalog.yaml")) {
            return new ObjectMapper(new YAMLFactory()).readValue(input, ToolCatalog.class).getTools().stream()
                    .filter(tool -> tool.getName().startsWith("knowledge.")).toList();
        }
    }
    @Test void exposesSixAdminOnlyToolsAndGatesAllWrites() throws Exception {
        assertThat(tools()).extracting(ToolDefinition::getName).containsExactlyInAnyOrder(
                "knowledge.list", "knowledge.get", "knowledge.batch_get", "knowledge.create", "knowledge.update", "knowledge.delete");
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
    @Test void nestedMutationContractAndBatchAreDiscoverable() throws Exception {
        var update = tools().stream().filter(t -> t.getName().equals("knowledge.update")).findFirst().orElseThrow();
        var validator = new ParameterValidator();
        assertThat(validator.validate(update, Map.of("id", UUID.randomUUID().toString(), "document", Map.of("title", "Example", "content", "Verified"),
                "policy", Map.of("status", "DRAFT", "answerVisibility", "private"), "preconditions", Map.of("revision", "a".repeat(32)))).isValid()).isTrue();
        assertThat(validator.validate(update, Map.of("id", UUID.randomUUID().toString(), "document", "not an object")).isValid()).isFalse();
    }
}
