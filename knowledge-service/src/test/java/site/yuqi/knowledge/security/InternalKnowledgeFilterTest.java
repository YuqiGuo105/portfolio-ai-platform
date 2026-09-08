package site.yuqi.knowledge.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import static org.assertj.core.api.Assertions.*;

class InternalKnowledgeFilterTest {
    @Test
    void requiresServiceCredentialAndFailsClosedWithoutConfiguration() throws Exception {
        for (String supplied : new String[]{null, "wrong", "service-secret"}) {
            var request = new MockHttpServletRequest("POST", "/internal/v1/knowledge/search");
            request.setServletPath("/internal/v1/knowledge/search");
            if (supplied != null) request.addHeader("X-Internal-Token", supplied);
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();
            new InternalKnowledgeFilter("service-secret").doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo("service-secret".equals(supplied) ? 200 : 401);
            assertThat(chain.getRequest() != null).isEqualTo("service-secret".equals(supplied));
        }
        var request = new MockHttpServletRequest();
        request.setServletPath("/internal/v1/knowledge/search");
        var response = new MockHttpServletResponse();
        new InternalKnowledgeFilter("").doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(503);
    }
}
