package site.yuqi.mcp.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import site.yuqi.mcp.model.ToolCatalog;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ProfileSourceContractTest {
    @Test void actualCatalogAcceptsUnfilteredPublicExperienceListing() throws Exception {
        try (var input = new ClassPathResource("tool-catalog.yaml").getInputStream()) {
            var catalog = new ObjectMapper(new YAMLFactory()).readValue(input, ToolCatalog.class);
            var tool = catalog.getTools().stream().filter(t -> t.getName().equals("admin.search_content")).findFirst().orElseThrow();
            assertThat(new ParameterValidator().validate(tool, Map.of("sourceType", "EXPERIENCE", "limit", 50)).isValid()).isTrue();
            assertThat(tool.getEndpoint().getPath()).isEqualTo("/api/admin/content");
        }
    }
}
