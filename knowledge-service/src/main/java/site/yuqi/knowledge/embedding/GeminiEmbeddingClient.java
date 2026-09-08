package site.yuqi.knowledge.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

/**
 * Gemini text-embedding-004 客户端。
 * API: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiEmbeddingClient implements EmbeddingClient {

    private final WebClient.Builder webClientBuilder;
    // Cache vectors only: source eligibility and content are rechecked on every retrieval.
    private final Cache<QueryKey, float[]> queryVectors = Caffeine.newBuilder()
            .maximumSize(256).expireAfterWrite(Duration.ofMinutes(10)).build();

    @Value("${knowledge.embedding.api-key}")
    private String apiKey;

    @Value("${knowledge.embedding.model:text-embedding-004}")
    private String model;

    @Value("${knowledge.embedding.dimension:768}")
    private int dimension;

    @Override
    public float[] embed(String text) {
        return requestEmbedding(text, dimension, "RETRIEVAL_DOCUMENT");
    }

    @Override
    public float[] embedQuery(String text, int dimensions) {
        if (text == null || text.isBlank() || text.length() > 8000) {
            throw new IllegalArgumentException("Query must contain 1 to 8000 characters");
        }
        return queryVectors.get(new QueryKey(model, dimensions, text),
                key -> requestEmbedding(key.text(), key.dimensions(), "RETRIEVAL_QUERY")).clone();
    }

    private record QueryKey(String model, int dimensions, String text) {}

    private float[] requestEmbedding(String text, int dimensions, String taskType) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":embedContent";

        Map<String, Object> body = Map.of(
                "model", "models/" + model,
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "outputDimensionality", dimensions,
                "taskType", taskType
        );

        var response = webClientBuilder.build()
                .post()
                .uri(url)
                .header("x-goog-api-key", apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .timeout(java.time.Duration.ofSeconds(6))
                .block();

        if (response == null || response.embedding() == null || response.embedding().values() == null
                || response.embedding().values().size() != dimensions) {
            throw new IllegalStateException("Missing or mismatched embedding dimensions");
        }
        return toFloatArray(response.embedding().values());
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private float[] toFloatArray(List<Double> values) {
        float[] arr = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i).floatValue();
        }
        return arr;
    }

    record EmbedResponse(Embedding embedding) {
        record Embedding(List<Double> values) {}
    }
}
