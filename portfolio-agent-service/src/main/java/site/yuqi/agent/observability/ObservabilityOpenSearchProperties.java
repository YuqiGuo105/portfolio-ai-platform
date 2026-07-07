package site.yuqi.agent.observability;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "observability.opensearch")
public class ObservabilityOpenSearchProperties {
    private String url;
    private String username;
    private String password;
}
