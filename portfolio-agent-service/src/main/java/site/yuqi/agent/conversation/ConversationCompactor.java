package site.yuqi.agent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import site.yuqi.agent.generation.GeminiGenerationService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationCompactor {

    private final RedisConversationStore redisStore;
    private final GeminiGenerationService generationService;
    private final ObjectMapper objectMapper;

    @Value("${agent.memory.max-turns-before-compact:10}")
    private int maxTurnsBeforeCompact;

    @Value("${agent.memory.token-budget:4000}")
    private int tokenBudget;

    @Value("${agent.memory.idle-compact-ms:600000}")
    private long idleCompactMs;

    @Value("${agent.memory.tool-result-max-chars:3000}")
    private int toolResultMaxChars;

    @Async
    public void compactIfNeeded(String conversationId, boolean force) {
        if (conversationId == null || conversationId.isBlank()) return;
        try {
            ConversationMemory memory = redisStore.load(conversationId);
            if (!force && !shouldCompact(memory)) return;
            compact(memory);
        } catch (Exception e) {
            log.debug("Conversation compaction skipped conversationId={}: {}", conversationId, e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${agent.memory.idle-scan-interval-ms:60000}")
    public void compactIdleSessions() {
        for (String conversationId : redisStore.scanConversationIds()) {
            try {
                ConversationMemory memory = redisStore.load(conversationId);
                if (isIdle(memory) && memory.getTurns() != null && memory.getTurns().size() > 6) {
                    compact(memory);
                }
            } catch (Exception e) {
                log.debug("Idle conversation compaction skipped conversationId={}: {}", conversationId, e.toString());
            }
        }
    }

    private boolean shouldCompact(ConversationMemory memory) {
        if (memory == null || memory.getTurns() == null || memory.getTurns().isEmpty()) return false;
        if (memory.getTurns().size() > maxTurnsBeforeCompact) return true;
        if (memory.getTurns().stream().mapToInt(t -> t.getApproxTokens() == null ? 0 : t.getApproxTokens()).sum()
                > tokenBudget) return true;
        if (isIdle(memory) && memory.getTurns().size() > 6) return true;
        return memory.getTurns().stream().anyMatch(this::largeToolResult);
    }

    private boolean isIdle(ConversationMemory memory) {
        if (memory == null || memory.getUpdatedAt() == null) return false;
        return Duration.between(memory.getUpdatedAt(), Instant.now()).toMillis() >= idleCompactMs;
    }

    private boolean largeToolResult(MemoryTurn turn) {
        if (turn == null || turn.getToolContext() == null) return false;
        Object result = turn.getToolContext().get("result");
        return result != null && String.valueOf(result).length() > toolResultMaxChars;
    }

    private void compact(ConversationMemory memory) {
        List<MemoryTurn> turns = memory.getTurns();
        if (turns == null || turns.size() <= 6) return;
        int keepStart = Math.max(0, turns.size() - 6);
        List<MemoryTurn> oldTurns = new ArrayList<>(turns.subList(0, keepStart));
        List<MemoryTurn> recentTurns = new ArrayList<>(turns.subList(keepStart, turns.size()));

        Map<String, Object> summary = summarize(memory, oldTurns);
        memory.setCompactSummary(summary);
        memory.setStructuredState(structuredState(memory.getStructuredState(), summary));
        memory.setTurns(recentTurns);
        redisStore.save(memory);
        log.info("Conversation compacted conversationId={} compressedTurns={} retainedTurns={}",
                memory.getConversationId(), oldTurns.size(), recentTurns.size());
    }

    private Map<String, Object> summarize(ConversationMemory memory, List<MemoryTurn> oldTurns) {
        String system = """
                You compact chat memory for an AI agent.
                Return strict JSON only. Do not include markdown.
                Preserve user goals, confirmed facts, resolved entities, tool outcomes, and open questions.
                Redact secrets, API keys, auth tokens, passwords, OTPs, and raw credentials.
                Required JSON keys: userGoals, keyFacts, entities, toolOutcomes, openQuestions.
                """;
        String prompt = "Existing compact summary:\n"
                + toJson(memory.getCompactSummary()) + "\n\nTurns to compact:\n"
                + toJson(oldTurns);
        try {
            String raw = generationService.generate(system, prompt);
            Map<String, Object> parsed = parseJsonObject(raw);
            if (!parsed.isEmpty()) return ensureSummaryShape(parsed);
        } catch (Exception e) {
            log.debug("Gemini compaction failed conversationId={}: {}", memory.getConversationId(), e.toString());
        }
        return fallbackSummary(memory, oldTurns);
    }

    private Map<String, Object> fallbackSummary(ConversationMemory memory, List<MemoryTurn> oldTurns) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userGoals", List.of());
        out.put("keyFacts", oldTurns.stream()
                .filter(t -> t.getContent() != null && !t.getContent().isBlank())
                .limit(8)
                .map(t -> t.getRole() + ": " + t.getContent())
                .toList());
        out.put("entities", memory.getStructuredState() == null ? Map.of() : memory.getStructuredState());
        out.put("toolOutcomes", oldTurns.stream()
                .filter(t -> t.getTargetTool() != null)
                .map(t -> Map.of("tool", t.getTargetTool(), "route", t.getRoute()))
                .toList());
        out.put("openQuestions", List.of());
        return out;
    }

    private Map<String, Object> structuredState(Map<String, Object> existing,
                                                Map<String, Object> summary) {
        Map<String, Object> state = existing == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(existing);
        Object entities = summary.get("entities");
        if (entities instanceof Map<?, ?>) state.put("entities", entities);
        Object openQuestions = summary.get("openQuestions");
        if (openQuestions != null) state.put("openQuestions", openQuestions);
        Object toolOutcomes = summary.get("toolOutcomes");
        if (toolOutcomes != null) state.put("toolOutcomes", toolOutcomes);
        return state;
    }

    private Map<String, Object> ensureSummaryShape(Map<String, Object> input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userGoals", input.getOrDefault("userGoals", List.of()));
        out.put("keyFacts", input.getOrDefault("keyFacts", List.of()));
        out.put("entities", input.getOrDefault("entities", Map.of()));
        out.put("toolOutcomes", input.getOrDefault("toolOutcomes", List.of()));
        out.put("openQuestions", input.getOrDefault("openQuestions", List.of()));
        return out;
    }

    private Map<String, Object> parseJsonObject(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return Map.of();
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return objectMapper.readValue(cleaned, new TypeReference<>() {});
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
