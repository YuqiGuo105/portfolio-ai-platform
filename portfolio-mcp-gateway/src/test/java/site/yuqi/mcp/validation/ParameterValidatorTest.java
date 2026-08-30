package site.yuqi.mcp.validation;

import org.junit.jupiter.api.Test;
import site.yuqi.mcp.model.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ParameterValidatorTest {

    private final ParameterValidator validator = new ParameterValidator();

    @Test
    void enforcesDeclarativeStringBounds() {
        ToolDefinition.ParameterSpec parameter = ToolDefinition.ParameterSpec.builder()
                .name("imageBase64")
                .type("string")
                .required(true)
                .minLength(4)
                .maxLength(8)
                .build();
        ToolDefinition tool = ToolDefinition.builder().parameters(List.of(parameter)).build();

        assertThat(validator.validate(tool, Map.of("imageBase64", "abcd")).isValid()).isTrue();
        assertThat(validator.validate(tool, Map.of("imageBase64", "abc")).getErrors())
                .containsExactly("Parameter imageBase64 is shorter than 4 characters.");
        assertThat(validator.validate(tool, Map.of("imageBase64", "abcdefghi")).getErrors())
                .containsExactly("Parameter imageBase64 exceeds 8 characters.");
    }
}
