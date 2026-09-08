package site.yuqi.agent.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiGenerationServiceFailureTest {

    private GeminiGenerationService service(HttpStatus status, String body, MediaType type) {
        WebClient.Builder client = WebClient.builder().exchangeFunction(request -> Mono.just(
                ClientResponse.create(status).header("Content-Type", type.toString()).body(body).build()));
        var service = new GeminiGenerationService(client, new ObjectMapper());
        ReflectionTestUtils.setField(service, "baseUrl", "https://synthetic.invalid");
        ReflectionTestUtils.setField(service, "apiKey", "synthetic-key");
        ReflectionTestUtils.setField(service, "generationModel", "synthetic-standard");
        ReflectionTestUtils.setField(service, "deepGenerationModel", "synthetic-deep");
        return service;
    }

    @Test
    void standardProviderFailureIsAnErrorNotAnAnswer() {
        var service = service(HttpStatus.SERVICE_UNAVAILABLE, "private-provider-detail", MediaType.TEXT_PLAIN);
        assertThatThrownBy(() -> service.streamGenerate("system", "question").collectList().block(Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Generation provider unavailable");
    }

    @Test
    void researchProviderFailureIsAnErrorNotAnAnswer() {
        var service = service(HttpStatus.TOO_MANY_REQUESTS, "private-provider-detail", MediaType.TEXT_PLAIN);
        assertThatThrownBy(() -> service.streamGenerateGrounded("system", "question").collectList().block(Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Research provider unavailable");
    }

    @Test
    void emptyStandardAndResearchResponsesFailExplicitly() {
        var service = service(HttpStatus.OK, "data: {}\n\n", MediaType.TEXT_EVENT_STREAM);
        assertThatThrownBy(() -> service.streamGenerate("system", "question").collectList().block(Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.streamGenerateGrounded("system", "question").collectList().block(Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validProviderResponseStillProducesText() {
        var service = service(HttpStatus.OK,
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Synthetic answer\"}]}}]}\n\n",
                MediaType.TEXT_EVENT_STREAM);
        assertThat(service.streamGenerate("system", "question").collectList().block(Duration.ofSeconds(3)))
                .containsExactly("Synthetic answer");
    }

    @Test
    void metadataAndDoneFramesDoNotBreakAValidStream() {
        var service = service(HttpStatus.OK,
                "data: {}\n\n"
                        + "data: {\"candidates\":[{\"content\":{\"parts\":[{},"
                        + "{\"text\":\"芝加哥和克里夫兰\"}]}}]}\n\n"
                        + "data: [DONE]\n\n",
                MediaType.TEXT_EVENT_STREAM);

        assertThat(service.streamGenerate("system", "他去过芝加哥和克里夫兰吗？")
                .collectList().block(Duration.ofSeconds(3)))
                .containsExactly("芝加哥和克里夫兰");
    }
}
