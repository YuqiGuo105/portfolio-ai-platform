package site.yuqi.knowledge.config;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenSearchConfig {

    @Bean(destroyMethod = "close")
    public RestClient opensearchRestClient(OpenSearchProperties props) {
        HttpHost host = HttpHost.create(props.getUrl());

        BasicCredentialsProvider credsProv = new BasicCredentialsProvider();
        credsProv.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(props.getUsername(), props.getPassword()));

        return RestClient.builder(host)
                .setHttpClientConfigCallback(b -> b.setDefaultCredentialsProvider(credsProv))
                .setRequestConfigCallback(b -> b
                        .setConnectTimeout(props.getConnectTimeoutMs())
                        .setSocketTimeout(props.getSocketTimeoutMs()))
                .build();
    }

    @Bean
    public OpenSearchClient openSearchClient(RestClient restClient) {
        return new OpenSearchClient(new RestClientTransport(restClient, new JacksonJsonpMapper()));
    }
}
