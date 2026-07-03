package site.yuqi.agent.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.Set;

/**
 * Server-derived identity attached to every authenticated request by
 * {@link SupabaseJwtAuthFilter}. Downstream code MUST read caller identity
 * from this object rather than trusting fields on the request body, because
 * the request body is fully controllable by any client that can reach the
 * public endpoint.
 *
 * <p>{@link Source} distinguishes the three trust postures:
 * <ul>
 *   <li>{@link Source#USER_JWT} — verified end-user token; roles are derived
 *       from the JWT and body-supplied roles are ignored.</li>
 *   <li>{@link Source#INTERNAL_PROXY} — a service-to-service call that
 *       presented the shared internal token. The Portfolio Next.js proxy
 *       already did its own Supabase JWT check before forwarding, so we
 *       honor the roles it stamped on the body.</li>
 *   <li>{@link Source#ANONYMOUS} — no bearer, allowed only when
 *       {@code agent.auth.allow-anonymous=true}. Roles are always empty so
 *       {@code PolicyGuard} only lets public read-only tools through.</li>
 * </ul>
 */
public record AuthenticatedPrincipal(
        Source source,
        String userId,
        String email,
        Set<String> roles) {

    public enum Source { USER_JWT, INTERNAL_PROXY, ANONYMOUS }

    /** Request attribute key used to hand the principal to controllers. */
    public static final String REQUEST_ATTR = "site.yuqi.agent.principal";

    public static AuthenticatedPrincipal anonymous() {
        return new AuthenticatedPrincipal(Source.ANONYMOUS, null, null, Collections.emptySet());
    }

    /**
     * Fetch the principal the filter attached, or a synthetic anonymous
     * principal if the filter didn't run (should only happen on
     * {@code /api/health} / actuator endpoints).
     */
    public static AuthenticatedPrincipal of(HttpServletRequest req) {
        Object attr = req.getAttribute(REQUEST_ATTR);
        return attr instanceof AuthenticatedPrincipal p ? p : anonymous();
    }

    /** Comma-separated roles for legacy {@code IntentRequest#userRoles}, or null when empty. */
    public String rolesCsv() {
        return roles == null || roles.isEmpty() ? null : String.join(",", roles);
    }
}
