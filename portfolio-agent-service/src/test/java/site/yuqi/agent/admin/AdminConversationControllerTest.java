package site.yuqi.agent.admin;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
