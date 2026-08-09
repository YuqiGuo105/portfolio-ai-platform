package site.yuqi.agent.observability;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Request-scoped W3C trace and business correlation identifiers. */
public record OperationContext(String traceId, String spanId, String correlationId) {

    private static final ThreadLocal<OperationContext> CURRENT = new ThreadLocal<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    public static OperationContext current() {
        OperationContext context = CURRENT.get();
        return context == null ? create(null, null) : context;
    }

    public static void set(OperationContext context) {
        CURRENT.set(context);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static OperationContext create(String traceparent, String correlationId) {
        String[] parts = traceparent == null ? new String[0] : traceparent.trim().split("-");
        String traceId = parts.length >= 4 && validHex(parts[1], 32) ? parts[1] : randomHex(16);
        String spanId = parts.length >= 4 && validHex(parts[2], 16) ? parts[2] : randomHex(8);
        String correlation = hasText(correlationId) ? correlationId.trim() : traceId;
        return new OperationContext(traceId, spanId, correlation);
    }

    private static boolean validHex(String value, int length) {
        return value != null && value.length() == length && value.matches("[0-9a-fA-F]+")
                && !value.matches("0+");
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
