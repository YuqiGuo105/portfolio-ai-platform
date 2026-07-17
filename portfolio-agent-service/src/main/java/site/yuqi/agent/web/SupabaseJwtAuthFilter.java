package site.yuqi.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed authentication filter for intent, chat, admin, and streaming RAG endpoints.
 *
 * <p>Cloud Run deploys the agent with {@code --allow-unauthenticated}, so
 * this filter is the ONLY defense against public writes. Without it, anyone
 * on the internet could POST {@code /api/intent}, {@code /api/chat}, or
 * {@code /api/rag/answer/stream} anonymously with a request body claiming
 * {@code userRoles=ADMIN} and hit admin/notification write tools.
 *
 * <p>Accepted credentials on protected endpoints:
 * <ol>
 *   <li>A Supabase HS256 JWT in {@code Authorization: Bearer <jwt>}.
 *       Signature is verified against {@code agent.auth.supabase-jwt-secret}.
 *       Roles are derived from the {@code app_metadata.roles} claim (or a
 *       top-level {@code roles} claim) if present, otherwise a signed-in
 *       user gets {@code VIEWER} and any email listed in
 *       {@code agent.auth.admin-emails} is additionally granted
 *       {@code EDITOR,PUBLISHER,ADMIN}.</li>
 *   <li>A shared service token in {@code Authorization: Bearer <token>}
 *       matching {@code agent.auth.internal-token}. This is the path the
 *       Portfolio Next.js proxy uses after doing its own Supabase JWT
 *       check server-side; when this token authenticates we honor
 *       {@code userEmail}/{@code userRoles} from the request body because
 *       the proxy has already validated them.</li>
 * </ol>
 *
 * <p>Fail-closed rules:
 * <ul>
 *   <li>Both {@code supabase-jwt-secret} and {@code internal-token} empty
 *       → all protected requests return 401 (unless {@code allow-anonymous=true},
 *       which is disabled by default).</li>
 *   <li>Bearer present but signature/expiry invalid → 401.</li>
 *   <li>Bearer present but no verifier configured → 401.</li>
 *   <li>No bearer with {@code allow-anonymous=false} → 401.</li>
 * </ul>
 *
 * <p>{@code /api/health}, {@code /actuator/health}, and (only when explicitly
 * enabled via {@code agent.auth.docs-exposed=true}) the springdoc paths bypass
 * this filter entirely.
 */
@Slf4j
@Component
public class SupabaseJwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> PROTECTED_PREFIXES = Set.of(
            "/api/intent", "/api/chat", "/api/admin");

    /** Endpoints where auth is optional — bearer validated if present, anonymous if absent. */
    private static final Set<String> AUTH_OPTIONAL_PREFIXES = Set.of(
            "/api/rag");

    /** Endpoints that must always be reachable without a bearer. */
    private static final Set<String> ALWAYS_OPEN = Set.of(
            "/api/health", "/actuator/health");

    private final ObjectMapper mapper = new ObjectMapper();

    private final byte[] jwtSecret;
    private final String internalToken;
    private final Set<String> adminEmails;
    private final boolean allowAnonymous;
    private final boolean docsExposed;

    public SupabaseJwtAuthFilter(
            @Value("${agent.auth.supabase-jwt-secret:}") String jwtSecretRaw,
            @Value("${agent.auth.internal-token:}") String internalTokenRaw,
            @Value("${agent.auth.admin-emails:}") String adminEmailsCsv,
            @Value("${agent.auth.allow-anonymous:false}") boolean allowAnonymous,
            @Value("${agent.auth.docs-exposed:false}") boolean docsExposed) {
        String secret = jwtSecretRaw == null ? "" : jwtSecretRaw.trim();
        this.jwtSecret = secret.isEmpty() ? null : secret.getBytes(StandardCharsets.UTF_8);
        this.internalToken = internalTokenRaw == null ? "" : internalTokenRaw.trim();
        this.adminEmails = parseAdminEmails(adminEmailsCsv);
        this.allowAnonymous = allowAnonymous;
        this.docsExposed = docsExposed;
        if (this.jwtSecret == null && this.internalToken.isEmpty() && !allowAnonymous) {
            log.warn("SupabaseJwtAuthFilter has no credentials configured: every " +
                    "/api/intent, /api/chat, /api/admin, and /api/rag request will 401. " +
                    "Set agent.auth.supabase-jwt-secret " +
                    "and/or agent.auth.internal-token, or set agent.auth.allow-anonymous=true " +
                    "for local development.");
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest req) {
        String p = req.getRequestURI();
        if (p == null) return true;
        if (ALWAYS_OPEN.contains(p)) return true;
        if (docsExposed && (p.startsWith("/v3/api-docs") || p.startsWith("/swagger-ui"))) {
            return true;
        }
        for (String prefix : PROTECTED_PREFIXES) {
            if (p.equals(prefix) || p.startsWith(prefix + "/")) return false;
        }
        for (String prefix : AUTH_OPTIONAL_PREFIXES) {
            if (p.equals(prefix) || p.startsWith(prefix + "/")) return false;
        }
        return true;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest req,
                                    @NonNull HttpServletResponse resp,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        String bearer = extractBearer(req.getHeader("Authorization"));

        // Determine if this is an auth-optional path (e.g. /api/rag)
        String uri = req.getRequestURI();
        boolean authOptional = false;
        if (uri != null) {
            for (String prefix : AUTH_OPTIONAL_PREFIXES) {
                if (uri.equals(prefix) || uri.startsWith(prefix + "/")) {
                    authOptional = true;
                    break;
                }
            }
        }

        AuthenticatedPrincipal principal;
        if (bearer == null) {
            if (!allowAnonymous && !authOptional) {
                reject(resp, "missing_bearer", "Missing Authorization: Bearer <token>.");
                return;
            }
            principal = AuthenticatedPrincipal.anonymous();
        } else if (!internalToken.isEmpty() && constantTimeEquals(bearer, internalToken)) {
            // Trusted proxy — controllers will honor body userEmail/userRoles.
            principal = new AuthenticatedPrincipal(
                    AuthenticatedPrincipal.Source.INTERNAL_PROXY, null, null, Set.of());
        } else if (jwtSecret != null) {
            principal = verifyJwt(bearer);
            if (principal == null) {
                reject(resp, "invalid_jwt", "Supabase JWT signature or expiry check failed.");
                return;
            }
        } else {
            reject(resp, "no_verifier",
                    "Server has no way to verify the supplied bearer token.");
            return;
        }

        req.setAttribute(AuthenticatedPrincipal.REQUEST_ATTR, principal);
        chain.doFilter(req, resp);
    }

    // ── JWT parsing (HS256 only, matching Supabase default) ─────────────

    private AuthenticatedPrincipal verifyJwt(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) return null;
        try {
            byte[] header = Base64.getUrlDecoder().decode(parts[0]);
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            byte[] sig = Base64.getUrlDecoder().decode(parts[2]);

            JsonNode headerJson = mapper.readTree(header);
            String alg = headerJson.path("alg").asText("");
            if (!"HS256".equalsIgnoreCase(alg)) {
                log.debug("JWT rejected: unsupported alg={}", alg);
                return null;
            }
            byte[] expected = hmacSha256(
                    (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, sig)) {
                return null;
            }
            JsonNode claims = mapper.readTree(payload);
            long exp = claims.path("exp").asLong(0);
            if (exp > 0 && System.currentTimeMillis() / 1000L >= exp) {
                log.debug("JWT rejected: expired at {}", exp);
                return null;
            }
            String sub = nullIfBlank(claims.path("sub").asText(null));
            String email = nullIfBlank(claims.path("email").asText(null));
            Set<String> roles = extractRoles(claims, email);
            return new AuthenticatedPrincipal(
                    AuthenticatedPrincipal.Source.USER_JWT, sub, email, roles);
        } catch (IllegalArgumentException e) {
            log.debug("JWT base64 decode failed: {}", e.toString());
            return null;
        } catch (Exception e) {
            log.debug("JWT verify failed: {}", e.toString());
            return null;
        }
    }

    private Set<String> extractRoles(JsonNode claims, String email) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode rolesNode = claims.path("app_metadata").path("roles");
        if (rolesNode.isMissingNode() || rolesNode.isNull()) {
            rolesNode = claims.path("roles");
        }
        if (rolesNode.isArray()) {
            for (JsonNode r : rolesNode) addRole(out, r.asText(""));
        } else if (rolesNode.isTextual()) {
            for (String s : rolesNode.asText("").split(",")) addRole(out, s);
        }
        // Every verified user is at least a VIEWER; email allowlist promotes to admin.
        out.add("VIEWER");
        if (email != null && adminEmails.contains(email.toLowerCase(Locale.ROOT))) {
            out.add("EDITOR");
            out.add("PUBLISHER");
            out.add("ADMIN");
        }
        return out;
    }

    private static void addRole(Set<String> out, String raw) {
        String t = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!t.isEmpty()) out.add(t);
    }

    private byte[] hmacSha256(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(jwtSecret, "HmacSHA256"));
        return mac.doFinal(data);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String extractBearer(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.length() < 7) return null;
        if (!h.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        String tok = h.substring(7).trim();
        return tok.isEmpty() ? null : tok;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> parseAdminEmails(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private void reject(HttpServletResponse resp, String code, String msg) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + msg + "\"}");
    }
}
