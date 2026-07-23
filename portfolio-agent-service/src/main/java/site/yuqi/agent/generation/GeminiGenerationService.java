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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Gemini model gateway for standard answers, deep grounded research, and
 * short utility generations. Each workload has an independently configurable
 * model and token budget.
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

    @Value("${agent.generation.model:gemini-2.5-flash}")
    private String generationModel;

    @Value("${agent.generation.deep-model:gemini-2.5-pro}")
    private String deepGenerationModel;

    @Value("${agent.generation.utility-model:gemini-2.5-flash-lite}")
    private String utilityModel;

    @Value("${agent.generation.max-output-tokens:2048}")
    private int maxOutputTokens;

    @Value("${agent.generation.deep-max-output-tokens:4096}")
    private int deepMaxOutputTokens;

    @Value("${agent.generation.utility-max-output-tokens:1024}")
    private int utilityMaxOutputTokens;

    @Value("${agent.generation.thinking-budget:0}")
    private int thinkingBudget;

    @Value("${agent.generation.utility-thinking-budget:0}")
    private int utilityThinkingBudget;

    public GeminiGenerationService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * Stream a standard answer from the low-latency generation model.
     *
     * @param systemPrompt system instructions
     * @param userMessage  user question with context
     * @return Flux of text deltas
     */
    public Flux<String> streamGenerate(String systemPrompt, String userMessage) {
        String url = baseUrl + "/models/" + generationModel + ":streamGenerateContent?alt=sse&key=" + apiKey;

        ObjectNode requestBody = buildRequest(
                systemPrompt, userMessage, false, maxOutputTokens, thinkingBudget);

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
     * Deep-mode generation backed by Gemini Google Search grounding. The
     * returned source list comes from provider grounding metadata, not from
     * model-authored URLs.
     */
    public Flux<GroundedChunk> streamGenerateGrounded(String systemPrompt, String userMessage) {
        String url = baseUrl + "/models/" + deepGenerationModel
                + ":streamGenerateContent?alt=sse&key=" + apiKey;
        ObjectNode requestBody = buildRequest(
                systemPrompt, userMessage, true, deepMaxOutputTokens, null);

        return webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(Duration.ofSeconds(90))
                .filter(line -> line != null && !line.isBlank())
                .map(this::extractGroundedChunk)
                .filter(chunk -> !chunk.text().isEmpty() || !chunk.sources().isEmpty())
                .onErrorResume(e -> {
                    log.error("Gemini grounded stream error: {}", e.getMessage());
                    return Flux.just(new GroundedChunk(
                            "[Error generating grounded response: " + e.getMessage() + "]", List.of()));
                });
    }

    /**
     * Non-streaming generation for short utility work such as formatting,
     * language alignment, compaction, and safety rewrites.
     */
    public String generate(String systemPrompt, String userMessage) {
        String url = baseUrl + "/models/" + utilityModel + ":generateContent?key=" + apiKey;

        ObjectNode requestBody = buildRequest(
                systemPrompt, userMessage, false, utilityMaxOutputTokens, utilityThinkingBudget);

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

    /**
     * Uses the low-cost utility model for bounded multimodal document
     * extraction. The caller owns origin, MIME, and size validation.
     */
    public String generateWithDocuments(String systemPrompt,
                                        String userMessage,
                                        List<InlineDocument> documents) {
        String url = baseUrl + "/models/" + utilityModel + ":generateContent?key=" + apiKey;
        ObjectNode requestBody = buildDocumentRequest(systemPrompt, userMessage, documents);

        String response = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody.toString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(45))
                .block();
        return extractFullText(response);
    }

    public String modelFor(boolean deepMode) {
        return deepMode ? deepGenerationModel : generationModel;
    }

    public String utilityModel() {
        return utilityModel;
    }

    private ObjectNode buildRequest(String systemPrompt,
                                    String userMessage,
                                    boolean webSearch,
                                    int outputTokenLimit,
                                    Integer requestThinkingBudget) {
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
        genConfig.put("maxOutputTokens", outputTokenLimit);
        genConfig.put("temperature", 0.7);
        if (requestThinkingBudget != null) {
            genConfig.putObject("thinkingConfig")
                    .put("thinkingBudget", requestThinkingBudget);
        }
        root.set("generationConfig", genConfig);

        if (webSearch) {
            ArrayNode tools = objectMapper.createArrayNode();
            tools.add(objectMapper.createObjectNode().set("google_search", objectMapper.createObjectNode()));
            root.set("tools", tools);
        }

        return root;
    }

    private ObjectNode buildDocumentRequest(String systemPrompt,
                                            String userMessage,
                                            List<InlineDocument> documents) {
        ObjectNode root = objectMapper.createObjectNode();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode system = objectMapper.createObjectNode();
            system.set("parts", objectMapper.createArrayNode()
                    .add(objectMapper.createObjectNode().put("text", systemPrompt)));
            root.set("systemInstruction", system);
        }

        ArrayNode parts = objectMapper.createArrayNode();
        parts.add(objectMapper.createObjectNode().put("text", userMessage));
        for (InlineDocument document : documents == null ? List.<InlineDocument>of() : documents) {
            parts.add(objectMapper.createObjectNode()
                    .put("text", "\nAttached file: " + document.name()));
            if (site.yuqi.agent.attachment.AttachmentPolicy.isTextMimeType(document.mimeType())) {
                parts.add(objectMapper.createObjectNode().put(
                        "text", new String(document.content(), StandardCharsets.UTF_8)));
            } else {
                ObjectNode inlineData = objectMapper.createObjectNode();
                inlineData.put("mimeType", document.mimeType());
                inlineData.put("data", Base64.getEncoder().encodeToString(document.content()));
                parts.add(objectMapper.createObjectNode().set("inlineData", inlineData));
            }
        }
        ObjectNode user = objectMapper.createObjectNode();
        user.put("role", "user");
        user.set("parts", parts);
        root.set("contents", objectMapper.createArrayNode().add(user));

        ObjectNode generationConfig = objectMapper.createObjectNode();
        generationConfig.put("maxOutputTokens", utilityMaxOutputTokens);
        generationConfig.put("temperature", 0.1);
        generationConfig.putObject("thinkingConfig")
                .put("thinkingBudget", utilityThinkingBudget);
        root.set("generationConfig", generationConfig);
        return root;
    }

    private GroundedChunk extractGroundedChunk(String sseData) {
        try {
            String json = sseData.startsWith("data:") ? sseData.substring(5).trim() : sseData;
            if (json.isBlank() || json.equals("[DONE]")) return new GroundedChunk("", List.of());
            JsonNode node = objectMapper.readTree(json);
            JsonNode candidate = node.path("candidates").path(0);
            String text = candidate.path("content").path("parts").path(0).path("text").asText("");
            List<GroundedSource> sources = new java.util.ArrayList<>();
            JsonNode chunks = candidate.path("groundingMetadata").path("groundingChunks");
            if (chunks.isArray()) {
                for (JsonNode chunk : chunks) {
                    JsonNode web = chunk.path("web");
                    String uri = web.path("uri").asText("");
                    if (!uri.isBlank()) {
                        sources.add(new GroundedSource(uri, web.path("title").asText(uri)));
                    }
                }
            }
            return new GroundedChunk(text, List.copyOf(sources));
        } catch (Exception e) {
            log.trace("Failed to parse grounded SSE chunk: {}", sseData);
            return new GroundedChunk("", List.of());
        }
    }

    public record GroundedChunk(String text, List<GroundedSource> sources) {}
    public record GroundedSource(String url, String title) {}
    public record InlineDocument(String name, String mimeType, byte[] content) {}

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
