package site.yuqi.agent.observability;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the OpenSearch client for observability event publishing.
 * Only active when observability.opensearch.url is configured.
 */
@Configuration
public class ObservabilityOpenSearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient observabilityRestClient(ObservabilityOpenSearchProperties props) {
        HttpHost host = HttpHost.create(props.getUrl());
        BasicCredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));

        return RestClient.builder(host)
                .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(creds))
                .setRequestConfigCallback(b -> b.setConnectTimeout(5000).setSocketTimeout(15000))
                .build();
    }

    @Bean
    public OpenSearchClient observabilityOpenSearchClient(RestClient observabilityRestClient) {
        return new OpenSearchClient(new RestClientTransport(observabilityRestClient, new JacksonJsonpMapper()));
    }
}
