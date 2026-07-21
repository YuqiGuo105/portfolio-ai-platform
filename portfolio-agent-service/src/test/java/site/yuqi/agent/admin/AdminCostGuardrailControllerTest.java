package site.yuqi.agent.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminCostGuardrailControllerTest {

    private final ChatBudgetService budgetService = mock(ChatBudgetService.class);
    private final AdminCostGuardrailController controller = new AdminCostGuardrailController(budgetService);

    @Test
    void viewerCannotReadCostGuardrailData() {
        MockHttpServletRequest request = requestWith(new AuthenticatedPrincipal(
                AuthenticatedPrincipal.Source.USER_JWT,
                "user-1",
                "viewer@example.com",
                Set.of("VIEWER")));

        ResponseEntity<?> response = controller.snapshot(request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void trustedProxyCanReadCostGuardrailData() {
        ChatBudgetService.BudgetSnapshot expected = new ChatBudgetService.BudgetSnapshot(
                "20260721",
                true,
                new BigDecimal("2.00"),
                new BigDecimal("0.50"),
                new BigDecimal("1.50"),
                new BigDecimal("0.12"),
                10,
                0,
                6,
                4,
                2,
                new BigDecimal("0.3333"),
                3,
                2,
                1,
                new BigDecimal("0.2500"),
                true,
                "NORMAL",
                Instant.parse("2026-07-22T00:00:00Z"));
        when(budgetService.snapshot()).thenReturn(expected);
        MockHttpServletRequest request = requestWith(new AuthenticatedPrincipal(
                AuthenticatedPrincipal.Source.INTERNAL_PROXY,
                null,
                null,
                Set.of()));

        ResponseEntity<?> response = controller.snapshot(request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(expected);
        verify(budgetService).snapshot();
    }

    private static MockHttpServletRequest requestWith(AuthenticatedPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedPrincipal.REQUEST_ATTR, principal);
        return request;
    }
}
