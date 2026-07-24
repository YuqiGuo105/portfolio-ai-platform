package site.yuqi.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "knowledge.opensearch")
public class OpenSearchProperties {
    private String url;
    private String username;
    private String password;
    private String knowledgeIndex = "knowledge-chunks-v1";
    private String contentIndex = "portfolio_content_current";
    private int embeddingDimension = 768;
    private int connectTimeoutMs = 5000;
    private int socketTimeoutMs = 30000;
}
