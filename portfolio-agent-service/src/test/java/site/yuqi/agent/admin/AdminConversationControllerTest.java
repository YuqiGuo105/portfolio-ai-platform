package site.yuqi.agent.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminConversationControllerTest {

    private final AdminConversationService service = mock(AdminConversationService.class);
    private final AdminConversationController controller = new AdminConversationController(service);

    @Test
    void viewerCannotReadConversationData() {
        MockHttpServletRequest request = requestWith(new AuthenticatedPrincipal(
                AuthenticatedPrincipal.Source.USER_JWT,
                "user-1",
                "viewer@example.com",
                Set.of("VIEWER")));

        ResponseEntity<?> response = controller.list("", 168, 50, request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(controller.diagnostics(java.util.UUID.randomUUID(), request).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    void adminGetsExactRunOrNotFound() {
        UUID runId = UUID.randomUUID();
        MockHttpServletRequest request = requestWith(new AuthenticatedPrincipal(
                AuthenticatedPrincipal.Source.USER_JWT, "admin-1", "admin@example.com", Set.of("ADMIN")));
        Map<String, Object> found = Map.of("runId", runId.toString(), "found", true);
        when(service.diagnostics(runId)).thenReturn(found);
        assertThat(controller.diagnostics(runId, request).getBody()).isEqualTo(found);
        assertThat(controller.diagnostics(runId, request).getStatusCode().value()).isEqualTo(200);
        when(service.diagnostics(runId)).thenReturn(Map.of("found", false));
        assertThat(controller.diagnostics(runId, request).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void trustedProxyCanReadConversationData() {
        AdminConversationService.ConversationResponse expected =
                new AdminConversationService.ConversationResponse(
                        List.of(),
                        new AdminConversationService.Summary(0, 0, 0, null),
                        0,
                        168);
        when(service.list("visitor", 168, 50)).thenReturn(expected);
        MockHttpServletRequest request = requestWith(new AuthenticatedPrincipal(
                AuthenticatedPrincipal.Source.INTERNAL_PROXY,
                null,
                null,
                Set.of()));

        ResponseEntity<?> response = controller.list("visitor", 168, 50, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(expected);
        verify(service).list("visitor", 168, 50);
    }

    private static MockHttpServletRequest requestWith(AuthenticatedPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedPrincipal.REQUEST_ATTR, principal);
        return request;
    }
}
