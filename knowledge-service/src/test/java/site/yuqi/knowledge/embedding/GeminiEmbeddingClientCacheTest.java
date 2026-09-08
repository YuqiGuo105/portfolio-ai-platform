package site.yuqi.knowledge.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class GeminiEmbeddingClientCacheTest {
    @Test
    void cachesQueryVectorsButNotFailuresAndReturnsDefensiveCopies() {
        AtomicInteger calls = new AtomicInteger();
        var client = new GeminiEmbeddingClient(WebClient.builder().exchangeFunction(request -> {
            if (calls.incrementAndGet() == 1) return Mono.error(new IllegalStateException("temporary outage"));
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                    .body("{\"embedding\":{\"values\":[0.1,0.2]}}").build());
        }));
        ReflectionTestUtils.setField(client, "model", "gemini-embedding-001");
        ReflectionTestUtils.setField(client, "apiKey", "test-key");
        assertThatThrownBy(() -> client.embedQuery("education", 2)).isInstanceOf(IllegalStateException.class);
        float[] first = client.embedQuery("education", 2);
        first[0] = 9;
        assertThat(client.embedQuery("education", 2)[0]).isEqualTo(0.1f);
        assertThat(calls).hasValue(2);
        ReflectionTestUtils.setField(client, "model", "another-model");
        client.embedQuery("education", 2);
        assertThat(calls).hasValue(3);
        assertThatThrownBy(() -> client.embedQuery(" ", 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
