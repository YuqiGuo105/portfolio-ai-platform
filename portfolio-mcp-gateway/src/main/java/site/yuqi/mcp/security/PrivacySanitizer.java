package site.yuqi.mcp.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes visitor-level fields and suppresses tiny buckets before analytics
 * responses are returned to the agent.
 */
public final class PrivacySanitizer {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "visitorId", "sessionId", "userId", "email", "ip", "ipAddress",
            "userAgent", "fingerprint", "distinctId", "deviceId", "rawEvents",
            "events", "visitors", "sessions"
    );

    private PrivacySanitizer() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> sanitizeAnalyticsResult(Map<String, Object> input, int minBucketCount) {
        Map<String, Object> sanitized = sanitizeMap(input == null ? Map.of() : input, minBucketCount);
        sanitized.put("privacyApplied", true);
        sanitized.put("minBucketCount", minBucketCount);
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(Object value, int minBucketCount) {
        if (value instanceof Map<?, ?> map) {
            return sanitizeMap((Map<String, Object>) map, minBucketCount);
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                Object sanitized = sanitizeValue(item, minBucketCount);
                if (sanitized != null) out.add(sanitized);
            }
            return out;
        }
        return value;
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> input, int minBucketCount) {
        if (isLowCountBucket(input, minBucketCount)) {
            return Map.of("suppressed", true, "reason", "low_count_bucket");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key == null || isSensitiveKey(key)) return;
            out.put(key, sanitizeValue(value, minBucketCount));
        });
        return out;
    }

    private static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEYS.contains(key)
                || key.toLowerCase().contains("email")
                || key.toLowerCase().contains("ipaddress");
    }

    private static boolean isLowCountBucket(Map<String, Object> bucket, int minBucketCount) {
        for (String key : List.of("count", "visits", "views", "uniqueVisitors", "pageViews")) {
            Object value = bucket.get(key);
            if (value instanceof Number n && n.longValue() > 0 && n.longValue() < minBucketCount) {
                return true;
            }
        }
        return false;
    }
}
