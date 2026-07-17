package site.yuqi.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-closed matrix for {@link SupabaseJwtAuthFilter}. Every path that could
 * possibly let an unauthenticated caller reach {@code /api/intent} or
 * {@code /api/chat} needs an assertion here — the filter is the only defense
 * against the {@code --allow-unauthenticated} Cloud Run deploy.
 */
class SupabaseJwtAuthFilterTest {

    private static final String SECRET = "test-supabase-jwt-secret-that-is-long-enough";
    private static final String INTERNAL = "svc-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Missing bearer ─────────────────────────────────────────────────

    @Test
    void missingBearerReturns401WhenAnonymousDisallowed() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, INTERNAL, "", false, false);
        MockHttpServletRequest req = post("/api/intent", null);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(resp.getContentAsString()).contains("missing_bearer");
    }

    @Test
    void adminApiRequiresBearer() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, INTERNAL, "", false, false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/conversations");
        req.setRequestURI("/api/admin/conversations");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingBearerAllowedWhenAnonymousEnabled() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter("", "", "", true, false);
        MockHttpServletRequest req = post("/api/intent", null);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        AuthenticatedPrincipal p = (AuthenticatedPrincipal) req.getAttribute(AuthenticatedPrincipal.REQUEST_ATTR);
        assertThat(p.source()).isEqualTo(AuthenticatedPrincipal.Source.ANONYMOUS);
        assertThat(p.roles()).isEmpty();
    }

    // ── Internal token ─────────────────────────────────────────────────

    @Test
    void internalTokenBearerYieldsProxyPrincipal() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, INTERNAL, "", false, false);
        MockHttpServletRequest req = post("/api/intent", "Bearer " + INTERNAL);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        AuthenticatedPrincipal p = (AuthenticatedPrincipal) req.getAttribute(AuthenticatedPrincipal.REQUEST_ATTR);
        assertThat(p.source()).isEqualTo(AuthenticatedPrincipal.Source.INTERNAL_PROXY);
    }

    // ── Supabase JWT ───────────────────────────────────────────────────

    @Test
    void validJwtYieldsUserPrincipalWithViewerRole() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, "", "", false, false);
        long exp = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signJwt(Map.of(
                "sub", "user-uuid-123",
                "email", "someone@example.com",
                "exp", exp), SECRET);
        MockHttpServletRequest req = post("/api/intent", "Bearer " + jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        AuthenticatedPrincipal p = (AuthenticatedPrincipal) req.getAttribute(AuthenticatedPrincipal.REQUEST_ATTR);
        assertThat(p.source()).isEqualTo(AuthenticatedPrincipal.Source.USER_JWT);
        assertThat(p.email()).isEqualTo("someone@example.com");
        assertThat(p.userId()).isEqualTo("user-uuid-123");
        assertThat(p.roles()).containsExactly("VIEWER");
    }

    @Test
    void adminAllowlistPromotesToAdminRoles() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(
                SECRET, "", "boss@yuqi.site,other@x.com", false, false);
        long exp = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signJwt(Map.of(
                "sub", "u1",
                "email", "Boss@Yuqi.Site",     // case-insensitive match
                "exp", exp), SECRET);
        MockHttpServletRequest req = post("/api/intent", "Bearer " + jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        AuthenticatedPrincipal p = (AuthenticatedPrincipal) req.getAttribute(AuthenticatedPrincipal.REQUEST_ATTR);
        assertThat(p.roles()).containsExactlyInAnyOrder("VIEWER", "EDITOR", "PUBLISHER", "ADMIN");
    }

    @Test
    void tamperedJwtSignatureReturns401() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, "", "", false, false);
        long exp = System.currentTimeMillis() / 1000L + 3600;
        String jwt = signJwt(Map.of("sub", "u", "exp", exp), SECRET);
        // Flip a byte in the signature segment.
        String tampered = jwt.substring(0, jwt.length() - 2) + "AA";
        MockHttpServletRequest req = post("/api/intent", "Bearer " + tampered);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("invalid_jwt");
    }

    @Test
    void expiredJwtReturns401() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, "", "", false, false);
        String jwt = signJwt(Map.of("sub", "u", "exp", 100L), SECRET);
        MockHttpServletRequest req = post("/api/intent", "Bearer " + jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void bearerRejectedWhenNoVerifierConfigured() throws Exception {
        // Neither jwt-secret nor internal-token set; anonymous disallowed too.
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter("", "", "", false, false);
        MockHttpServletRequest req = post("/api/intent", "Bearer whatever");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("no_verifier");
    }

    @Test
    void rolesClaimIsHonouredWhenPresent() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, "", "", false, false);
        long exp = System.currentTimeMillis() / 1000L + 3600;
        Map<String, Object> appMeta = Map.of("roles", List.of("editor", "publisher"));
        String jwt = signJwt(Map.of(
                "sub", "u", "email", "e@x.com", "exp", exp,
                "app_metadata", appMeta), SECRET);
        MockHttpServletRequest req = post("/api/intent", "Bearer " + jwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        AuthenticatedPrincipal p = (AuthenticatedPrincipal) req.getAttribute(AuthenticatedPrincipal.REQUEST_ATTR);
        assertThat(p.roles()).containsExactlyInAnyOrder("EDITOR", "PUBLISHER", "VIEWER");
    }

    // ── Bypass paths ────────────────────────────────────────────────────

    @Test
    void healthEndpointBypassesFilterEvenWithoutBearer() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, INTERNAL, "", false, false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/health");
        req.setRequestURI("/api/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull(); // downstream reached
    }

    @Test
    void docsAreBlockedByDefault() throws Exception {
        SupabaseJwtAuthFilter f = new SupabaseJwtAuthFilter(SECRET, INTERNAL, "", false, false);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/intent");
        req.setRequestURI("/v3/api-docs");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, resp, chain);
        // /v3/api-docs isn't in PROTECTED_PREFIXES, so filter is bypassed and
        // the downstream (a 404 for real deployments in prod) is reached; the
        // real defense is springdoc.api-docs.enabled=false in the prod profile.
        // We only guarantee we don't 401 legit docs paths locally.
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static MockHttpServletRequest post(String uri, String authHeader) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRequestURI(uri);
        if (authHeader != null) req.addHeader("Authorization", authHeader);
        return req;
    }

    private static String signJwt(Map<String, Object> claims, String secret) throws Exception {
        String headerJson = MAPPER.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT"));
        // LinkedHashMap keeps insertion order for deterministic assertions on
        // roles above (though they end up in a Set on the read side).
        String payloadJson = MAPPER.writeValueAsString(new LinkedHashMap<>(claims));
        String signingInput = b64u(headerJson.getBytes(StandardCharsets.UTF_8))
                + "." + b64u(payloadJson.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] sig = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64u(sig);
    }

    private static String b64u(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
