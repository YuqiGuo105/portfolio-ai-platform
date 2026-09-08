package site.yuqi.knowledge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Raw evidence is service-to-service only, independently of Cloud Run ingress. */
@Component
public class InternalKnowledgeFilter extends OncePerRequestFilter {
    private final String token;

    public InternalKnowledgeFilter(@Value("${knowledge.internal-token:}") String token) {
        this.token = token;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getServletPath().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Internal-Token");
        if (token == null || token.isBlank()) {
            response.sendError(503, "Knowledge authentication unavailable");
            return;
        }
        if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            response.sendError(401, "Internal authentication required");
            return;
        }
        chain.doFilter(request, response);
    }
}
