package site.yuqi.mcp.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import site.yuqi.mcp.model.ToolCatalog;
import site.yuqi.mcp.model.ToolDefinition;
import site.yuqi.mcp.security.RiskGateValidator;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AlertBotFilterContractTest {
    private ToolDefinition tool(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/tool-catalog.yaml")) {
            return new ObjectMapper(new YAMLFactory()).readValue(input, ToolCatalog.class).getTools().stream()
                    .filter(tool -> name.equals(tool.getName())).findFirst().orElseThrow();
        }
    }

    @Test
    void nestedFiltersAreDiscoverableAndRemainAdminOnly() throws Exception {
        var prepare = tool("alerts.prepare_change");
        var draft = tool("visitor.rule_test");
        var apply = tool("alerts.apply_change");
        for (var tool : java.util.List.of(prepare, draft, apply, tool("visitor.explain_match"))) {
            assertThat(tool.getRequiredRole()).isEqualTo("ADMIN");
        }
        var validator = new ParameterValidator();
        assertThat(validator.validate(prepare, Map.of("action", "UPDATE", "ruleId", 1,
                "patch", Map.of("filters", Map.of("bot", "EXCLUDE")), "reason", "Exclude detected bots")).isValid()).isTrue();
        assertThat(draft.getParameters()).anySatisfy(parameter -> {
            assertThat(parameter.getName()).isEqualTo("filters");
            assertThat(parameter.getType()).isEqualTo("object");
            assertThat(parameter.getDescription()).contains("EXCLUDE", "unknown");
        });
        assertThat(new RiskGateValidator().check(apply, Map.of()).allowed()).isFalse();
        assertThat(new RiskGateValidator().check(apply, Map.of("_confirmed", true)).allowed()).isTrue();
    }
}
