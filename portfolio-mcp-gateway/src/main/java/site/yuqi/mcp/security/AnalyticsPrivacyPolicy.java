package site.yuqi.mcp.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.yuqi.mcp.model.ToolDefinition;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

/**
 * Privacy gate for analytics tools. These are READ tools, but they can still
 * leak user behavior if the time window is too narrow or the caller asks for
 * raw visitor attributes.
 */
@Component
public class AnalyticsPrivacyPolicy {

    private static final Set<String> FORBIDDEN_ARGS = Set.of(
            "visitorId", "sessionId", "userId", "email", "ip", "ipAddress",
            "userAgent", "fingerprint", "distinctId", "includeRaw", "raw"
    );

    private static final Set<String> ALLOWED_DIMENSIONS = Set.of(
            "country", "region", "city", "deviceCategory", "deviceType",
            "page", "pagePath", "referrer", "source"
    );

    @Value("${mcp.analytics.min-window-days:7}")
    private long minWindowDays;

    public Outcome check(ToolDefinition tool, Map<String, Object> args) {
        if (tool == null || tool.getName() == null || !tool.getName().startsWith("analytics.")) {
            return Outcome.ok();
        }

        for (String forbidden : FORBIDDEN_ARGS) {
            if (args.containsKey(forbidden)) {
                return Outcome.fail("Analytics tools cannot accept visitor-specific or raw-data parameter: "
                        + forbidden);
            }
        }

        Object dimensions = args.get("dimensions");
        if (dimensions instanceof Iterable<?> iterable) {
            for (Object dimension : iterable) {
                if (dimension == null || !ALLOWED_DIMENSIONS.contains(dimension.toString())) {
                    return Outcome.fail("Analytics dimension is not privacy-approved: " + dimension);
                }
            }
        }

        if (!Boolean.TRUE.equals(args.get("_confirmedTimeRange"))) {
            return Outcome.fail("Analytics time range requires explicit user confirmation.");
        }

        LocalDate start = parseDate(args.get("startDate"));
        LocalDate end = parseDate(args.get("endDate"));
        if (start == null || end == null) {
            return Outcome.fail("Analytics tools require ISO startDate and endDate.");
        }
        if (end.isBefore(start)) {
            return Outcome.fail("Analytics endDate must be on or after startDate.");
        }

        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days < minWindowDays) {
            return Outcome.fail("Analytics time range must be at least " + minWindowDays
                    + " days for visitor privacy.");
        }

        Object limit = args.get("limit");
        if (limit instanceof Number n && n.intValue() > 25) {
            return Outcome.fail("Analytics result limit cannot exceed 25.");
        }
        if (limit instanceof String s) {
            try {
                if (Integer.parseInt(s) > 25) {
                    return Outcome.fail("Analytics result limit cannot exceed 25.");
                }
            } catch (NumberFormatException ignored) {
                return Outcome.fail("Analytics result limit must be an integer.");
            }
        }

        return Outcome.ok();
    }

    private static LocalDate parseDate(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    public record Outcome(boolean allowed, String reason) {
        public static Outcome ok() { return new Outcome(true, null); }
        public static Outcome fail(String reason) { return new Outcome(false, reason); }
    }
}
