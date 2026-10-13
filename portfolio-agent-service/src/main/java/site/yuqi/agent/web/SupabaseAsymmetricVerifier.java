package site.yuqi.agent.web;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import java.time.Duration;
import java.util.Map;

/** Cached, fixed-origin JWKS verification for sessions issued after key migration. */
final class SupabaseAsymmetricVerifier {
    private final NimbusJwtDecoder decoder;

    SupabaseAsymmetricVerifier(String supabaseUrl) {
        if (supabaseUrl == null || supabaseUrl.isBlank()) { decoder = null; return; }
        String issuer = supabaseUrl.trim().replaceAll("/+$", "") + "/auth/v1";
        decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/.well-known/jwks.json")
                .jwsAlgorithms(algorithms -> {
                    algorithms.clear();
                    algorithms.add(SignatureAlgorithm.ES256);
                    algorithms.add(SignatureAlgorithm.RS256);
                })
                .restOperations(new RestTemplateBuilder().setConnectTimeout(Duration.ofSeconds(2))
                        .setReadTimeout(Duration.ofSeconds(3)).build()).build();
        OAuth2TokenValidator<Jwt> accessToken = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getSubject() != null
                    && !jwt.getSubject().isBlank() && jwt.getAudience().contains("authenticated")
                    && !Boolean.TRUE.equals(jwt.getClaim("is_anonymous"));
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "A signed-in Supabase access token is required.", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), accessToken));
    }

    boolean isConfigured() { return decoder != null; }

    Map<String, Object> verify(String token) {
        if (decoder == null || token.length() > 16384) throw new BadJwtException("Invalid access token");
        return decoder.decode(token).getClaims();
    }
}
