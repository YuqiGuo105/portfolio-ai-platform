package site.yuqi.agent.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Streams answer tokens from Gemini 2.5 Pro via streamGenerateContent.
 * Returns a Flux of text deltas suitable for SSE streaming.
 */
@Slf4j
@Service
public class GeminiGenerationService {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${agent.model.gemini.api-key:${GEMINI_API_KEY:}}")
    private String apiKey;

    @Value("${agent.model.gemini.base-url:${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta}}")
    private String baseUrl;

    @Value("${agent.generation.model:gemini-2.5-pro}")
    private String generationModel;

    @Value("${agent.generation.max-output-tokens:4096}")
    private int maxOutputTokens;

    public GeminiGenerationService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Stream answer generation from Gemini Pro.
     *
     * @param systemPrompt system instructions
     * @param userMessage  user question with context
     * @return Flux of text deltas
     */
    public Flux<String> streamGenerate(String systemPrompt, String userMessage) {
        String url = baseUrl + "/models/" + generationModel + ":streamGenerateContent?alt=sse&key=" + apiKey;

        ObjectNode requestBody = buildRequest(systemPrompt, userMessage);

        return webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(60))
                .filter(line -> line != null && !line.isBlank())
                .map(this::extractTextDelta)
                .filter(delta -> delta != null && !delta.isEmpty())
                .onErrorResume(e -> {
                    log.error("Gemini stream error: {}", e.getMessage());
                    return Flux.just("[Error generating response: " + e.getMessage() + "]");
                });
    }

    /**
     * Non-streaming generation (for short answers).
     */
    public String generate(String systemPrompt, String userMessage) {
        String url = baseUrl + "/models/" + generationModel + ":generateContent?key=" + apiKey;

        ObjectNode requestBody = buildRequest(systemPrompt, userMessage);

        String response = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        return extractFullText(response);
    }

    private ObjectNode buildRequest(String systemPrompt, String userMessage) {
        ObjectNode root = objectMapper.createObjectNode();

        // System instruction
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysInstruction = objectMapper.createObjectNode();
            ObjectNode sysPart = objectMapper.createObjectNode();
            sysPart.put("text", systemPrompt);
            ArrayNode sysParts = objectMapper.createArrayNode();
            sysParts.add(sysPart);
            sysInstruction.set("parts", sysParts);
            root.set("systemInstruction", sysInstruction);
        }

        // User content
        ArrayNode contents = objectMapper.createArrayNode();
        ObjectNode userContent = objectMapper.createObjectNode();
        userContent.put("role", "user");
        ObjectNode userPart = objectMapper.createObjectNode();
        userPart.put("text", userMessage);
        ArrayNode userParts = objectMapper.createArrayNode();
        userParts.add(userPart);
        userContent.set("parts", userParts);
        contents.add(userContent);
        root.set("contents", contents);

        // Generation config
        ObjectNode genConfig = objectMapper.createObjectNode();
        genConfig.put("maxOutputTokens", maxOutputTokens);
        genConfig.put("temperature", 0.7);
        root.set("generationConfig", genConfig);

        return root;
    }

    private String extractTextDelta(String sseData) {
        try {
            // SSE data line: "data: {...}"
            String json = sseData.startsWith("data:") ? sseData.substring(5).trim() : sseData;
            if (json.isBlank() || json.equals("[DONE]")) return null;
            JsonNode node = objectMapper.readTree(json);
            JsonNode candidates = node.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).path("text").asText("");
                }
            }
        } catch (Exception e) {
            log.trace("Failed to parse SSE chunk: {}", sseData);
        }
        return null;
    }

    private String extractFullText(String responseJson) {
        try {
            JsonNode node = objectMapper.readTree(responseJson);
            JsonNode candidates = node.path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && !parts.isEmpty()) {
                    return parts.get(0).path("text").asText("");
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Gemini response", e);
        }
        return "";
    }
}
