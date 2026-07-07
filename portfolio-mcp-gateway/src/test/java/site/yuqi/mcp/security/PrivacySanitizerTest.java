package site.yuqi.mcp.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacySanitizerTest {

    @Test
    void removesSensitiveFieldsAndSuppressesSmallBuckets() {
        Map<String, Object> sanitized = PrivacySanitizer.sanitizeAnalyticsResult(Map.of(
                "uniqueVisitors", 42,
                "email", "person@example.com",
                "items", List.of(
                        Map.of("path", "/projects", "views", 12, "ip", "127.0.0.1"),
                        Map.of("path", "/secret", "views", 2)
                )
        ), 5);

        assertThat(sanitized).containsEntry("privacyApplied", true);
        assertThat(sanitized).doesNotContainKey("email");
        assertThat((List<?>) sanitized.get("items"))
                .hasSize(2)
                .anySatisfy(item -> assertThat(castMap(item)).containsEntry("suppressed", true));
        Map<String, Object> first = castMap(((List<?>) sanitized.get("items")).get(0));
        assertThat(first).doesNotContainKey("ip");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
