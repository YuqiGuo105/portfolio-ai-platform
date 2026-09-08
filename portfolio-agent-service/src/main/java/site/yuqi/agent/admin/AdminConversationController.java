package site.yuqi.agent.admin;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/conversations")
@RequiredArgsConstructor
public class AdminConversationController {

    private final AdminConversationService conversationService;

    @GetMapping("/runs/{runId}")
    public ResponseEntity<?> diagnostics(@PathVariable UUID runId, HttpServletRequest request) {
        if (!isAdminCaller(AuthenticatedPrincipal.of(request))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "forbidden", "message", "Admin access required."));
        }
        Map<String, Object> result = conversationService.diagnostics(runId);
        return ResponseEntity.status(Boolean.TRUE.equals(result.get("found"))
                ? HttpStatus.OK : HttpStatus.NOT_FOUND).body(result);
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(name = "q", defaultValue = "") String query,
            @RequestParam(defaultValue = "168") int hours,
            @RequestParam(defaultValue = "50") int limit,
            HttpServletRequest request) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(request);
        if (!isAdminCaller(principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "forbidden", "message", "Admin access required."));
        }
        return ResponseEntity.ok(conversationService.list(query, hours, limit));
    }

    private boolean isAdminCaller(AuthenticatedPrincipal principal) {
        if (principal.source() == AuthenticatedPrincipal.Source.INTERNAL_PROXY) return true;
        return principal.roles() != null && principal.roles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role));
    }
}
