package site.yuqi.agent.admin;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.util.Map;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/admin/cost-guardrail")
@RequiredArgsConstructor
public class AdminCostGuardrailController {

    private final ChatBudgetService budgetService;

    @GetMapping
    public ResponseEntity<?> snapshot(HttpServletRequest request) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(request);
        if (!isAdminCaller(principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "forbidden", "message", "Admin access required."));
        }
        return ResponseEntity.ok(budgetService.snapshot());
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody BudgetUpdateRequest body, HttpServletRequest request) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(request);
        if (!isAdminCaller(principal)) return forbidden();
        return ResponseEntity.ok(budgetService.updateBudget(
                body == null ? null : body.limitUsd(), body == null ? null : body.enabled()));
    }

    @GetMapping("/explain-spike")
    public ResponseEntity<?> explainSpike(HttpServletRequest request) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(request);
        if (!isAdminCaller(principal)) return forbidden();
        return ResponseEntity.ok(budgetService.explainSpike());
    }

    private ResponseEntity<Map<String, String>> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", "Admin access required."));
    }

    public record BudgetUpdateRequest(BigDecimal limitUsd, Boolean enabled) {}

    private boolean isAdminCaller(AuthenticatedPrincipal principal) {
        if (principal.source() == AuthenticatedPrincipal.Source.INTERNAL_PROXY) return true;
        return principal.roles() != null && principal.roles().stream()
                .anyMatch(role -> "ADMIN".equalsIgnoreCase(role));
    }
}
