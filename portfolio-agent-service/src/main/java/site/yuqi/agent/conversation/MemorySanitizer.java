package site.yuqi.agent.conversation;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class MemorySanitizer {

    private static final Set<String> REDACT_KEYS = Set.of(
            "apikey", "api_key", "authorization", "token", "secret", "password",
            "servicerolekey", "service_role_key",
            "openaiapikey", "openai_api_key",
            "geminiapikey", "gemini_api_key",
            "otp", "one_time_password", "verificationcode", "verification_code",
            "codehash", "code_hash");

    public Object sanitize(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (shouldRedact(key)) {
                    out.put(key, "***");
                } else {
                    out.put(key, sanitize(e.getValue()));
                }
            }
            return out;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            int count = 0;
            for (Object item : iterable) {
                if (count++ >= 30) break;
                out.add(sanitize(item));
            }
            return out;
        }
        if (value instanceof String s) {
            return truncate(s, 2_000);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> sanitizeMap(Map<String, Object> value) {
        Object sanitized = sanitize(value);
        return sanitized instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public String truncate(String value, int max) {
        if (value == null) return "";
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() <= max ? compact : compact.substring(0, max);
    }

    private boolean shouldRedact(String key) {
        if (key == null) return false;
        String normalized = key.replace("-", "_").toLowerCase(Locale.ROOT);
        return REDACT_KEYS.contains(normalized) || REDACT_KEYS.contains(normalized.replace("_", ""));
    }
}
