package site.yuqi.mcp.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uniform error envelope for all gateway failures (validation,
 * authorization, downstream HTTP, timeout).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StructuredErrorResponse {

    private String code;
    private String message;
    private String tool;
    private Map<String, Object> details;

    public static StructuredErrorResponse of(String code, String message) {
        return new StructuredErrorResponse(code, message, null, null);
    }

    public static StructuredErrorResponse of(String code, String message, String tool) {
        return new StructuredErrorResponse(code, message, tool, null);
    }

    public StructuredErrorResponse withDetail(String key, Object value) {
        if (details == null) details = new LinkedHashMap<>();
        details.put(key, value);
        return this;
    }
}
