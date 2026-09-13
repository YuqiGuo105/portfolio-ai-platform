package site.yuqi.mcp.validation;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Bounded, allow-listed translation from MCP objects to the existing admin query API. */
public final class VisitorQueryArguments {
    private static final Map<String, Integer> FILTERS = Map.of(
            "q", 200, "event", 100, "path", 512, "country", 100, "city", 100,
            "device", 100, "browser", 100, "referrer", 512, "sessionId", 256);

    private VisitorQueryArguments() {}

    public static Map<String, Object> normalize(String toolName, Map<String, Object> args) {
        boolean session = "visitor.get_session_events".equals(toolName);
        if (!session && !"visitor.search_events".equals(toolName)) {
            throw invalid("Unsupported visitor detail tool.");
        }
        var input = new HashMap<>(args);
        input.keySet().removeIf(key -> key != null && key.startsWith("_"));
        checkKeys(input, session ? Set.of("sessionId", "window", "page")
                : Set.of("filter", "window", "page"), "arguments");
        var result = new HashMap<String, Object>();

        var window = object(input, "window");
        checkKeys(window, Set.of("from", "to", "hours"), "window");
        Instant from;
        Instant to;
        if (window.containsKey("from") || window.containsKey("to")) {
            if (window.containsKey("hours")) throw invalid("Use window.hours or window.from/to, not both.");
            from = instant(window.get("from"), "window.from");
            to = instant(window.get("to"), "window.to");
            if (!from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) {
                throw invalid("window.from/to must define a positive range of at most 31 days.");
            }
        } else {
            int hours = integer(window, "hours", 24, 1, 744);
            to = Instant.now();
            from = to.minus(Duration.ofHours(hours));
        }
        result.put("from", from.toString());
        result.put("to", to.toString());

        var page = object(input, "page");
        checkKeys(page, Set.of("number", "size"), "page");
        result.put("page", integer(page, "number", 0, 0, 10_000));
        result.put("size", integer(page, "size", 25, 1, 100));
        result.put("includeAdmin", false);
        if (session) {
            result.put("sessionId", text(input.get("sessionId"), "sessionId", 256));
        } else {
            var filter = object(input, "filter");
            var allowed = new java.util.HashSet<>(FILTERS.keySet());
            allowed.add("includeAdmin");
            checkKeys(filter, allowed, "filter");
            FILTERS.forEach((key, max) -> {
                if (filter.containsKey(key)) result.put(key, text(filter.get(key), "filter." + key, max));
            });
            if (filter.containsKey("includeAdmin")) {
                if (!(filter.get("includeAdmin") instanceof Boolean)) {
                    throw invalid("filter.includeAdmin must be a boolean.");
                }
                result.put("includeAdmin", filter.get("includeAdmin"));
            }
        }
        return result;
    }

    private static Map<?, ?> object(Map<?, ?> input, String key) {
        if (!input.containsKey(key)) return Map.of();
        if (input.get(key) instanceof Map<?, ?> value) return value;
        throw invalid(key + " must be an object.");
    }

    private static void checkKeys(Map<?, ?> input, Set<String> allowed, String label) {
        if (!allowed.containsAll(input.keySet())) throw invalid("Unsupported field in " + label + ".");
    }

    private static int integer(Map<?, ?> input, String key, int fallback, int min, int max) {
        if (!input.containsKey(key)) return fallback;
        Object value = input.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != Math.rint(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max) {
            throw invalid(key + " must be an integer between " + min + " and " + max + ".");
        }
        return number.intValue();
    }

    private static String text(Object input, String label, int max) {
        if (!(input instanceof String value) || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(label + " must be a nonblank string of at most " + max + " characters without control characters.");
        }
        return value.trim();
    }

    private static Instant instant(Object value, String label) {
        try {
            return Instant.parse(text(value, label, 64));
        } catch (DateTimeParseException e) {
            throw invalid(label + " must be an ISO-8601 timestamp with a timezone.");
        }
    }

    private static IllegalArgumentException invalid(String message) {
        // Never include supplied values or unrecognized keys in client-visible errors.
        return new IllegalArgumentException(message);
    }
}
