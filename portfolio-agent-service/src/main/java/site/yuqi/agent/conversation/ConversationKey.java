package site.yuqi.agent.conversation;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Derives a deterministic, non-reversible conversation id for Redis memory.
 *
 * <p>The stable parts are session id + device id. IP is included only as a
 * weak tiebreaker because mobile networks and NAT can shift underneath users.
 */
public final class ConversationKey {

    private ConversationKey() {
    }

    public static String derive(HttpServletRequest request, String sessionId) {
        String stableSession = blankTo(sessionId, "anonymous-session");
        String deviceId = firstNonBlank(
                header(request, "X-CW-Device-Id"),
                header(request, "X-Device-Id"),
                header(request, "X-Client-Device-Id"),
                cookie(request, "cw_device_id"),
                cookie(request, "deviceId"),
                cookie(request, "chatDeviceId"),
                userAgentBucket(request));
        String stableDevice = blankTo(deviceId, "unknown-device");
        String material = "conv:" + stableSession + "|" + stableDevice;
        // IP is only a fallback tiebreaker when no stable device signal exists.
        // Including it unconditionally breaks a conversation when a mobile IP
        // changes or when one request crosses a trusted BFF.
        if ("unknown-device".equals(stableDevice) || "unknown-ua".equals(stableDevice)) {
            material += "|" + firstForwardedIp(request);
        }
        return "conv_" + sha256(material).substring(0, 40);
    }

    private static String firstForwardedIp(HttpServletRequest request) {
        String forwarded = header(request, "X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return firstNonBlank(
                header(request, "X-Real-IP"),
                header(request, "CF-Connecting-IP"),
                request != null ? request.getRemoteAddr() : null);
    }

    private static String userAgentBucket(HttpServletRequest request) {
        String ua = header(request, "User-Agent");
        if (ua == null || ua.isBlank()) return "unknown-ua";
        String lower = ua.toLowerCase();
        if (lower.contains("bot") || lower.contains("spider") || lower.contains("crawler")) return "bot";
        if (lower.contains("mobile") || lower.contains("iphone") || lower.contains("android")) return "mobile";
        if (lower.contains("ipad") || lower.contains("tablet")) return "tablet";
        return "desktop";
    }

    private static String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private static String cookie(HttpServletRequest request, String name) {
        if (request == null || request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
