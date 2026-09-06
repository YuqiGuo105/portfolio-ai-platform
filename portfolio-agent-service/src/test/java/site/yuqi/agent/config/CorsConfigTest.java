package site.yuqi.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {
    private static class TestRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() { return getCorsConfigurations(); }
    }

    @Test
    void localOriginsAreLimitedToCredentiallessSignedUploads() {
        TestRegistry registry = new TestRegistry();
        new CorsConfig("https://www.yuqi.site").addCorsMappings(registry);
        CorsConfiguration uploads = registry.configurations().get("/api/rag/attachments/*/content");
        assertEquals("http://127.0.0.1:3062", uploads.checkOrigin("http://127.0.0.1:3062"));
        assertEquals("http://localhost:3062", uploads.checkOrigin("http://localhost:3062"));
        assertEquals("https://www.yuqi.site", uploads.checkOrigin("https://www.yuqi.site"));
        assertNull(uploads.checkOrigin("https://attacker.example"));
        assertNull(uploads.checkOrigin("http://localhost.attacker.example:3062"));
        assertFalse(uploads.getAllowCredentials());
        assertNull(uploads.checkHttpMethod(org.springframework.http.HttpMethod.DELETE));
        assertNull(registry.configurations().get("/api/**").checkOrigin("http://127.0.0.1:3062"));
    }
}
