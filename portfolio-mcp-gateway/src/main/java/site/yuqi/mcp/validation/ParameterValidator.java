package site.yuqi.mcp.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import site.yuqi.mcp.model.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Type / presence / shape checks on inbound tool arguments against the
 * declarative parameter list in {@link ToolDefinition}. Permissive by design
 * — unknown keys are allowed (forwarded as-is), but required keys must be
 * present and non-empty.
 */
@Component
public class ParameterValidator {

    public ValidationResult validate(ToolDefinition tool, Map<String, Object> args) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> safe = args == null ? Map.of() : args;

        if (tool.getParameters() != null) {
            for (ToolDefinition.ParameterSpec p : tool.getParameters()) {
                Object v = safe.get(p.getName());
                if (p.isRequired() && isBlank(v)) {
                    errors.add("Missing required parameter: " + p.getName());
                    continue;
                }
                if (v != null && !matchesType(v, p.getType())) {
                    errors.add("Parameter " + p.getName() + " has wrong type "
                            + "(expected " + p.getType() + ", got " + v.getClass().getSimpleName() + ").");
                }
            }
        }

        return ValidationResult.builder()
                .valid(errors.isEmpty())
                .errors(errors)
                .build();
    }

    private boolean isBlank(Object v) {
        if (v == null) return true;
        if (v instanceof String s) return s.isBlank();
        if (v instanceof java.util.Collection<?> c) return c.isEmpty();
        return false;
    }

    private boolean matchesType(Object v, String type) {
        if (type == null) return true;
        return switch (type.toLowerCase()) {
            case "string"  -> v instanceof String;
            case "integer" -> v instanceof Integer || v instanceof Long
                    || (v instanceof String s && s.matches("-?\\d+"));
            case "number"  -> v instanceof Number;
            case "boolean" -> v instanceof Boolean;
            case "array"   -> v instanceof java.util.Collection<?>;
            case "object"  -> v instanceof Map<?, ?>;
            default        -> true; // unknown declared type — accept
        };
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationResult {
        private boolean valid;
        private List<String> errors;
    }
}
