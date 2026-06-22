package site.yuqi.mcp.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Declarative tool contract loaded from {@code tool-catalog.yaml}. The
 * structure is intentionally lenient (extra fields are ignored) so future
 * sprints can add metadata without breaking older nodes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolDefinition {

    private String name;
    private ToolMode mode;
    private String description;
    private String requiredRole;
    private RiskLevel riskLevel;
    private boolean dryRunSupported;
    private boolean confirmRequired;
    private String confirmationMethod;
    private List<ParameterSpec> parameters;
    private Endpoint endpoint;
    private String note;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ParameterSpec {
        private String name;
        private String type;
        private boolean required;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Endpoint {
        private String target;
        private String method;
        private String path;
    }
}
