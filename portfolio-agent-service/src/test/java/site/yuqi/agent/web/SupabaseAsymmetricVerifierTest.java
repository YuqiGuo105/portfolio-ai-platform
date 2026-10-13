package site.yuqi.agent.web;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import static org.assertj.core.api.Assertions.*;

class SupabaseAsymmetricVerifierTest {
    HttpServer server;
    ECKey key;
    String base;

    @BeforeEach void setup() throws Exception {
        key = new ECKeyGenerator(Curve.P_256).keyID("current").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v1/.well-known/jwks.json", exchange -> {
            byte[] bytes = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }

    JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(base + "/auth/v1").audience("authenticated")
                .subject("test-user").claim("email", "user@example.test")
                .issueTime(new Date()).expirationTime(Date.from(Instant.now().plusSeconds(300)));
    }
    String sign(JWTClaimsSet claims, ECKey signer) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("current").build(), claims);
        jwt.sign(new ECDSASigner(signer));
        return jwt.serialize();
    }

    @Test void newSignaturesReachTheExistingUserPrincipalPath() throws Exception {
        var filter = new SupabaseJwtAuthFilter("", "internal", "", false, false, base);
        var request = new MockHttpServletRequest("POST", "/api/intent");
        request.addHeader("Authorization", "Bearer " + sign(claims().build(), key));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        var principal = (AuthenticatedPrincipal) request.getAttribute(AuthenticatedPrincipal.REQUEST_ATTR);
        assertThat(principal.source()).isEqualTo(AuthenticatedPrincipal.Source.USER_JWT);
        assertThat(principal.roles()).containsExactly("VIEWER");
    }

    @Test void rejectsWrongIssuerAudienceExpiredMissingExpiryAndAnonymousTokens() throws Exception {
        var verifier = new SupabaseAsymmetricVerifier(base);
        for (var claims : java.util.List.of(claims().issuer("https://evil.test").build(),
                claims().audience("oauth-client").build(), claims().expirationTime(null).build(),
                claims().expirationTime(Date.from(Instant.now().minusSeconds(120))).build(),
                claims().claim("is_anonymous", true).build())) {
            var token = sign(claims, key);
            assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(RuntimeException.class);
        }
    }

    @Test void tamperingFailsClosedAndNeverBecomesAnAnonymousRequest() throws Exception {
        var filter = new SupabaseJwtAuthFilter("", "internal", "", false, false, base);
        var request = new MockHttpServletRequest("POST", "/api/rag/answer/stream");
        request.addHeader("Authorization", "Bearer " + sign(claims().build(), new ECKeyGenerator(Curve.P_256).generate()));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
    }
}
