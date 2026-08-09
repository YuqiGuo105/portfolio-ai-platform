package site.yuqi.agent.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class OperationContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        OperationContext context = OperationContext.create(
                request.getHeader("traceparent"),
                firstNonBlank(request.getHeader("X-Correlation-Id"), request.getHeader("X-Request-Id")));
        OperationContext.set(context);
        response.setHeader("X-Correlation-Id", context.correlationId());
        try {
            filterChain.doFilter(request, response);
        } finally {
            OperationContext.clear();
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
