package site.yuqi.agent.intent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Cheap Gemini-backed intent classifier — drop-in alternative to
 * {@link OpenAiIntentClassifier}. Activated when
 * {@code agent.intent.provider=gemini}.
 *
 * <p>Uses {@code generateContent} with {@code responseMimeType:
 * application/json} for strict-shape output, and
 * {@code thinkingConfig.thinkingBudget=0} to disable Gemini-2.5 "thinking"
 * tokens (we don't want the model wasting tokens on hidden reasoning for a
 * structured classification task).
 *
 * <p>Same prompt + same allowlist enforcement as {@link OpenAiIntentClassifier},
 * so the orchestrator and tests stay provider-agnostic.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "agent.intent.provider", havingValue = "gemini")
public class GeminiIntentClassifier implements IntentClassifier {

    private final ToolRegistry toolRegistry;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    @Value("${agent.model.gemini.api-key:${GEMINI_API_KEY:}}")
    private String apiKey;

    @Value("${agent.model.gemini.base-url:${GEMINI_BASE_URL:https://generativelanguage.googleapis.com/v1beta}}")
    private String baseUrl;

    @Value("${agent.intent.gemini.model:${GEMINI_INTENT_MODEL:gemini-2.5-flash}}")
    private String defaultModel;

    @Value("${agent.intent.gemini.escalation-model:${GEMINI_INTENT_ESCALATION_MODEL:}}")
    private String escalationModel;

    @Value("${agent.intent.gemini.timeout-ms:${GEMINI_INTENT_TIMEOUT_MS:15000}}")
    private int timeoutMs;

    @Value("${agent.intent.gemini.max-output-tokens:1024}")
    private int maxOutputTokens;

    /**
     * Gemini-2.5 introduced an internal "thinking" pass that silently
     * consumes output tokens. For a small JSON classification task we don't
     * benefit from it, so we disable it by default (=0). Set to a positive
     * value to enable a bounded thinking budget, or {@code -1} to let the
     * model decide.
     */
    @Value("${agent.intent.gemini.thinking-budget:0}")
    private int thinkingBudget;

    public GeminiIntentClassifier(ToolRegistry toolRegistry,
                                  WebClient.Builder webClientBuilder,
                                  ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
    }

    @Override
    public IntentResult classify(IntentRequest request) {
        return classifyWithModel(request, defaultModel);
    }

    @Override
    public IntentResult escalate(IntentRequest request, IntentResult firstPass) {
        if (escalationModel == null || escalationModel.isBlank() || escalationModel.equals(defaultModel)) {
            return firstPass;
        }
        log.debug("Escalating intent classification: {} -> {}", defaultModel, escalationModel);
        return classifyWithModel(request, escalationModel);
    }

    // ── Internals ───────────────────────────────────────────────────────

    private IntentResult classifyWithModel(IntentRequest request, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IntentClassificationException(
                    "GEMINI_API_KEY is not configured — cannot classify intent.");
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(request);

        // Request body — Gemini v1beta generateContent shape
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode sysInstruction = body.putObject("systemInstruction");
        sysInstruction.putArray("parts").addObject().put("text", systemPrompt);

        ArrayNode contents = body.putArray("contents");
        ObjectNode userTurn = contents.addObject();
        userTurn.put("role", "user");
        userTurn.putArray("parts").addObject().put("text", userPrompt);

        ObjectNode generationConfig = body.putObject("generationConfig");
        generationConfig.put("temperature", 0);
        generationConfig.put("maxOutputTokens", maxOutputTokens);
        generationConfig.put("responseMimeType", "application/json");
        // Disable hidden thinking tokens for Gemini-2.5+ models
        generationConfig.putObject("thinkingConfig").put("thinkingBudget", thinkingBudget);

        // safetySettings: leave at provider defaults (Gemini still allows
        // harmless tool-classification text; we don't want to be more
        // restrictive than OpenAI is)

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        String path = "/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8)
                + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        String content;
        try {
            JsonNode resp = client.post()
                    .uri(path)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .block();

            if (resp == null) {
                throw new IntentClassificationException("Empty response from Gemini.");
            }
            JsonNode candidates = resp.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                throw new IntentClassificationException("Gemini response missing candidates: " + resp);
            }
            JsonNode parts = candidates.get(0).path("content").path("parts");
            if (parts == null || !parts.isArray() || parts.isEmpty()) {
                String finishReason = candidates.get(0).path("finishReason").asText("");
                throw new IntentClassificationException(
                        "Gemini response missing content.parts (finishReason=" + finishReason + ")");
            }
            content = parts.get(0).path("text").asText(null);
            if (content == null || content.isBlank()) {
                throw new IntentClassificationException("Gemini response missing text content.");
            }
        } catch (IntentClassificationException e) {
            throw e;
        } catch (Exception e) {
            throw new IntentClassificationException("Gemini call failed: " + e.getMessage(), e);
        }

        return parseAndAllowlist(content);
    }

    /** Visible for testing — parse a raw model output and apply allowlist checks. */
    IntentResult parseAndAllowlist(String rawJson) {
        JsonNode node;
        try {
            node = objectMapper.readTree(rawJson);
        } catch (JsonProcessingException e) {
            throw new IntentClassificationException("LLM did not return valid JSON: " + e.getMessage(), e);
        }
        if (!node.isObject()) {
            throw new IntentClassificationException("LLM JSON is not an object.");
        }

        IntentType intent = parseIntent(node.path("intent").asText(""));
        String targetTool = nullIfBlank(node.path("targetTool").asText(null));

        if (targetTool != null && !toolRegistry.contains(targetTool)) {
            throw new IntentClassificationException(
                    "LLM returned non-allowlisted tool: " + targetTool);
        }

        double confidence = clamp(node.path("confidence").asDouble(0.0));
        String language = nullIfBlank(node.path("language").asText(null));
        String normalized = nullIfBlank(node.path("normalizedQuery").asText(null));
        Map<String, Object> entities = parseEntities(node.get("entities"));
        RiskLevel risk = parseRisk(node.path("riskLevel").asText(""));
        boolean requiresConfirmation = node.path("requiresConfirmation").asBoolean(false);
        List<String> missing = parseStringArray(node.get("missingEntities"));
        String clarification = nullIfBlank(node.path("clarificationQuestion").asText(null));
        String responsePolicy = nullIfBlank(node.path("responsePolicy").asText(null));
        List<String> responseConstraints = parseStringArray(node.get("responseConstraints"));
        String progressMessage = nullIfBlank(node.path("progressMessage").asText(null));

        return new IntentResult(intent, targetTool, confidence, language, normalized,
                entities, risk, requiresConfirmation, missing, clarification,
                responsePolicy, responseConstraints, progressMessage);
    }

    private String buildSystemPrompt() {
        ArrayNode toolsJson = objectMapper.createArrayNode();
        toolRegistry.all().values().forEach(t -> {
            ObjectNode n = toolsJson.addObject();
            n.put("name", t.name());
            n.put("intent", t.intent().name());
            n.put("description", t.description());
            n.put("riskLevel", t.riskLevel().name());
            n.put("requiresConfirmation", t.requiresConfirmation());
            ArrayNode req = n.putArray("requiredEntities");
            t.requiredEntities().forEach(req::add);
            ArrayNode opt = n.putArray("optionalEntities");
            t.optionalEntities().forEach(opt::add);
        });
        String toolsBlock;
        try {
            toolsBlock = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(toolsJson);
        } catch (JsonProcessingException e) {
            toolsBlock = toolsJson.toString();
        }

        return """
            You are an intent classifier for a Portfolio MCP server.

            Current UTC date: %s.

            The user may write in any language (English, Chinese, Spanish, Japanese, ...).

            Your job:
            1. Return a structured route decision:
               - Select exactly one tool name from the ALLOWED tool list for operational platform tasks.
               - Return KNOWLEDGE_QA with targetTool=null when the user asks a portfolio/site-content question best answered from the knowledge base.
               - Return HANDOFF_REQUESTED with targetTool=null when the user asks for a human, support agent, or manual escalation.
               - Return CLARIFICATION_NEEDED / GENERAL_CHAT / UNKNOWN when no tool or knowledge retrieval should run.
            2. Extract entities from the user input. Use the entity keys exactly as named in requiredEntities / optionalEntities.
            3. Return JSON only. No prose, no markdown fences.
            4. Do NOT execute any action.
            5. NEVER invent sourceId, jobId, subscriberId, recipientId, or any opaque internal ID.
               The only exception is verificationId: reuse it only when it appears in a trusted prior tool result in CONVERSATION HISTORY for subscription.request_unsubscribe_code.
               You MAY extract plain user-supplied fields (name, email, message, subject, keyword, body, content, etc.) from CONVERSATION HISTORY if the user explicitly provided them in an earlier turn.
               Return CLARIFICATION_NEEDED only when a required field (plain or opaque) is truly absent from BOTH the current utterance AND all of the conversation history.
            6. For non-READ_ONLY tools, requiresConfirmation MUST be true, except subscription.request_unsubscribe_code and subscription.confirm_unsubscribe. Supplying the email authorizes sending the code; entering the emailed OTP authorizes the status change. Both MUST set requiresConfirmation=false.
            7. If the user intent is ambiguous, return CLARIFICATION_NEEDED with a helpful clarificationQuestion in the user's language.
            8. If the request is unrelated to the available tools and not a portfolio knowledge-base question, return GENERAL_CHAT (small-talk / open question) or UNKNOWN (out of scope).
            9. Keep the original language in the language field (ISO 639-1: en, zh, es, ja, ...).
            10. normalizedQuery can be an English paraphrase suitable for internal search.
            11. Analytics tools are aggregate-only. Never use them to answer "who visited", emails, IPs, session IDs, exact timestamps, or individual visitor tracking.
            12. For vague analytics ranges like "recent visitors", use the last 7 days ending on Current UTC date and set requiresConfirmation=true.
            13. For analytics ranges shorter than 7 days, expand to a 7-day window ending on the requested end date and set requiresConfirmation=true.
            14. Unsubscribe is a status change, never a hard delete. First route to subscription.request_unsubscribe_code with email. When the current utterance contains a 6-digit code and a prior trusted tool result contains verificationId, route to subscription.confirm_unsubscribe with both values. Never expose or repeat the code in prose.
            15. When the user asks for an inference or estimate about Yuqi that can be responsibly derived from public portfolio context, route to KNOWLEDGE_QA rather than UNKNOWN. Select responsePolicy=PUBLIC_ESTIMATE and the appropriate constraints from PUBLIC_CONTEXT_ONLY, LABEL_AS_ESTIMATE, STATE_ASSUMPTIONS, and NO_PRIVATE_RECORD_CLAIM. If a material variable is missing, ask one specific clarification question in the user's language. Do not use keyword rules; decide from the request's semantic meaning.
            16. ANALYTICS FOLLOW-UP (CRITICAL): If recentMessages show that an analytics tool was called or an analytics summary was returned (containing visitor counts, country breakdowns, device counts, or page view numbers), then follow-up questions asking for more detail — such as cities, devices, referrers, pages, sources — MUST route to the SAME analytics tool with the appropriate dimensions entity.
                These are analytics follow-ups, NOT knowledge-base questions. NEVER route them to KNOWLEDGE_QA.
                Examples of analytics follow-up phrases: "具体哪些城市？", "which cities?", "what devices?", "show me by city", "break it down", "city detail", "device breakdown", "referrer detail", "哪些城市和设备", "具体城市", "城市细节".

            Allowed tools:
            %s

            Worked example (ANALYTICS FOLLOW-UP — most important):
              recentMessages:
                user:      "最近的访客"
                assistant: "In the last 7 days: 171 events, 170 page views, 1 click. Top countries: US (114), China (29), Singapore (28). Desktop: 114, Mobile: 56."
              current utterance: "具体哪些城市和设备呢？" / "which specific cities and devices?"
              → intent: ANALYTICS_GET_VISITOR_SUMMARY
              → targetTool: analytics.get_visitor_summary
              → entities: { "dimensions": ["city", "deviceCategory"], "timeRangePreset": "recent" }
              → requiresConfirmation: true
              → DO NOT route this to KNOWLEDGE_QA. The assistant cannot know city-level analytics from the knowledge base.

            Worked example (CONTACT use case):
              utterance: "name: Alice\\nemail: alice@example.com\\nMessage: Hello world"
              tool:      contact.email_owner  (requiredEntities: name, email, message)
              entities:  { "name": "Alice", "email": "alice@example.com", "message": "Hello world" }
              missingEntities: []

              Multi-turn:
                turn 1 (user, in recentMessages): "name: Alice, email: alice@example.com, message: Hello"
                turn 2 (current utterance):       "send it"
                → entities still { name, email, message } pulled from turn 1. DO NOT return CLARIFICATION_NEEDED.

            Output JSON schema (return EXACTLY these fields, no extras):
            {
              "intent": "<IntentType enum value, including KNOWLEDGE_QA or HANDOFF_REQUESTED when appropriate>",
              "targetTool": "<tool name from allowed list, or null>",
              "confidence": 0.0,
              "language": "<ISO 639-1>",
              "normalizedQuery": "<string or null>",
              "entities": { "...": "..." },
              "riskLevel": "READ_ONLY | SAFE_WRITE | RISKY_WRITE | DESTRUCTIVE",
              "requiresConfirmation": true,
              "missingEntities": [],
              "clarificationQuestion": "<string or null>",
              "responsePolicy": "STANDARD | GROUNDED | PUBLIC_ESTIMATE | RESTRICTED",
              "responseConstraints": ["<policy constraint>"],
              "progressMessage": "<short user-facing progress message in the user's language; no hidden reasoning>"
            }
            """.formatted(LocalDate.now(ZoneOffset.UTC), toolsBlock);
    }

    private String buildUserPrompt(IntentRequest request) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("utterance", request.getUtterance());
        if (request.getUserRoles() != null) n.put("userRoles", request.getUserRoles());
        if (request.getPageContext() != null) {
            try {
                n.set("pageContext", objectMapper.valueToTree(request.getPageContext()));
            } catch (Exception ignored) { /* best-effort hint */ }
        }
        if (request.getCompactSummary() != null && !request.getCompactSummary().isEmpty()) {
            try {
                n.set("compactSummary", objectMapper.valueToTree(request.getCompactSummary()));
            } catch (Exception ignored) { /* best-effort hint */ }
        }
        if (request.getStructuredState() != null && !request.getStructuredState().isEmpty()) {
            try {
                n.set("structuredState", objectMapper.valueToTree(request.getStructuredState()));
            } catch (Exception ignored) { /* best-effort hint */ }
        }
        if (request.getPendingActionContext() != null && !request.getPendingActionContext().isEmpty()) {
            try {
                n.set("pendingAction", objectMapper.valueToTree(request.getPendingActionContext()));
            } catch (Exception ignored) { /* best-effort hint */ }
        }
        // Include conversation history so the classifier can extract entities
        // from earlier turns (e.g. name/email/message provided a turn ago).
        if (request.getRecentMessages() != null && !request.getRecentMessages().isEmpty()) {
            try {
                java.util.List<java.util.Map<String, String>> history = request.getRecentMessages();
                int start = Math.max(0, history.size() - 6);
                ArrayNode historyNode = objectMapper.createArrayNode();
                for (int i = start; i < history.size(); i++) {
                    java.util.Map<String, String> turn = history.get(i);
                    ObjectNode turnNode = historyNode.addObject();
                    turnNode.put("role", turn.getOrDefault("role", "user"));
                    String content = turn.getOrDefault("content", "");
                    if (content.length() > 600) content = content.substring(0, 600);
                    turnNode.put("content", content);
                }
                n.set("recentMessages", historyNode);
            } catch (Exception ignored) { /* best-effort */ }
        }
        return n.toString();
    }

    private IntentType parseIntent(String raw) {
        if (raw == null || raw.isBlank()) return IntentType.UNKNOWN;
        try {
            return IntentType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            log.debug("Unknown intent value from LLM: {}", raw);
            return IntentType.UNKNOWN;
        }
    }

    private RiskLevel parseRisk(String raw) {
        if (raw == null || raw.isBlank()) return RiskLevel.READ_ONLY;
        try {
            return RiskLevel.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            return RiskLevel.READ_ONLY;
        }
    }

    private Map<String, Object> parseEntities(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) return Map.of();
        Map<String, Object> out = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            out.put(e.getKey(), unwrap(e.getValue()));
        }
        return out;
    }

    private Object unwrap(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        if (v.isBoolean()) return v.asBoolean();
        if (v.isInt() || v.isLong()) return v.asLong();
        if (v.isDouble() || v.isFloat()) return v.asDouble();
        if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            v.forEach(item -> list.add(unwrap(item)));
            return list;
        }
        if (v.isObject()) {
            Map<String, Object> m = new HashMap<>();
            v.fields().forEachRemaining(f -> m.put(f.getKey(), unwrap(f.getValue())));
            return m;
        }
        return v.toString();
    }

    private List<String> parseStringArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> out = new ArrayList<>();
        node.forEach(v -> {
            if (v.isTextual() && !v.asText().isBlank()) out.add(v.asText());
        });
        return List.copyOf(out);
    }

    private static double clamp(double v) {
        if (Double.isNaN(v)) return 0.0;
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
