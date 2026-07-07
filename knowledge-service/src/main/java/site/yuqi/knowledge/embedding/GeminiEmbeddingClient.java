package site.yuqi.knowledge.embedding;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Gemini text-embedding-004 客户端。
 * API: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:embedContent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiEmbeddingClient implements EmbeddingClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${knowledge.embedding.api-key}")
    private String apiKey;

    @Value("${knowledge.embedding.model:text-embedding-004}")
    private String model;

    @Value("${knowledge.embedding.dimension:768}")
    private int dimension;

    @Override
    public float[] embed(String text) {
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + model + ":embedContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "model", "models/" + model,
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "outputDimensionality", dimension
        );

        var response = webClientBuilder.build()
                .post()
                .uri(url)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(EmbedResponse.class)
                .block();

        if (response == null || response.embedding() == null) {
            log.error("Gemini embedding returned null for text length={}", text.length());
            return new float[dimension];
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
