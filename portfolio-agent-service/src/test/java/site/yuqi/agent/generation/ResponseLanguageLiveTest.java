package site.yuqi.agent.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.agent.intent.GeminiIntentClassifier;
import site.yuqi.agent.intent.IntentRequest;
import site.yuqi.agent.intent.ToolRegistry;
import site.yuqi.agent.language.ResponseLanguagePolicy;
import site.yuqi.agent.observability.EventRecorder;
import site.yuqi.agent.safety.OutputSafetyContext;
import site.yuqi.agent.safety.SafetyService;
import site.yuqi.agent.safety.SafetyVerdict;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Opt-in provider test: public/synthetic facts only, no database or external write tools. */
@EnabledIfEnvironmentVariable(named = "RUN_GEMINI_LANGUAGE_TEST", matches = "true")
class ResponseLanguageLiveTest {
    @Test
    void plansGeneratesAndVerifiesCurrentTurnLanguage() {
        String key = System.getenv("GEMINI_API_KEY");
        assertThat(key).as("Live test requires a server-side API key").isNotBlank();
        var mapper = new ObjectMapper();
        var generation = new GeminiGenerationService(WebClient.builder(), mapper);
        ReflectionTestUtils.setField(generation, "apiKey", key);
        ReflectionTestUtils.setField(generation, "baseUrl", "https://generativelanguage.googleapis.com/v1beta");
        ReflectionTestUtils.setField(generation, "generationModel", "gemini-2.5-flash");
        ReflectionTestUtils.setField(generation, "utilityModel", "gemini-2.5-flash-lite");
        ReflectionTestUtils.setField(generation, "maxOutputTokens", 256);
        ReflectionTestUtils.setField(generation, "utilityMaxOutputTokens", 256);
        var classifier = new GeminiIntentClassifier(mock(ToolRegistry.class), WebClient.builder(), mapper);
        ReflectionTestUtils.setField(classifier, "apiKey", key);
        ReflectionTestUtils.setField(classifier, "baseUrl", "https://generativelanguage.googleapis.com/v1beta");
        ReflectionTestUtils.setField(classifier, "defaultModel", "gemini-2.5-flash-lite");
        ReflectionTestUtils.setField(classifier, "timeoutMs", 15000);
        ReflectionTestUtils.setField(classifier, "maxOutputTokens", 1024);
        var safety = new SafetyService(WebClient.builder(), mock(EventRecorder.class), mapper);
        ReflectionTestUtils.setField(safety, "enabled", true);
        ReflectionTestUtils.setField(safety, "geminiApiKey", key);
        ReflectionTestUtils.setField(safety, "safetyModel", "gemini-2.5-flash-lite");
        ReflectionTestUtils.setField(safety, "safetyTimeoutMs", 15000);
        String system = (String) ReflectionTestUtils.getField(AgentPipelineService.class, "SYSTEM_PROMPT");
        String evidence = "Yuqi Guo (verified Chinese name: 郭育奇) is a Software Engineer at Goldman Sachs. No salary information is provided.";
        List<Case> cases = List.of(
                new Case("reported-chinese", "过玉琪的介绍，他的职业是什么？他在哪里工作？", "zh", "Earlier answer in English."),
                new Case("mixed-terms", "介绍一下 Yuqi Guo 在 Goldman Sachs 的工作。", "zh", "Earlier answer in English."),
                new Case("switch-to-english", "What does Yuqi do, and where does he work?", "en", "他是一名软件工程师。"),
                new Case("explicit-english", "他的职业是什么？请用英文回答。", "en", "他在高盛工作。"),
                new Case("explicit-chinese", "What does Yuqi do? Answer in Chinese.", "zh", "He is an engineer."),
                new Case("spanish", "¿Cuál es la profesión de Yuqi y dónde trabaja?", "es", "He is an engineer."),
                new Case("japanese", "Yuqiの職業と勤務先を日本語で教えてください。", "ja", "He is an engineer."));
        for (Case c : cases) {
            var history = List.of(Map.of("role", "assistant", "content", c.history()));
            var intent = classifier.classify(IntentRequest.builder().sessionId("language-test")
                    .utterance(c.question()).recentMessages(history).build());
            assertThat(intent.language()).as(c.id() + " planning").startsWith(c.language());
            String prompt = "## Knowledge Base Context\n" + evidence + "\n## Recent Conversation Turns\n"
                    + c.history() + "\n## Resolved Search Intent (not evidence)\n" + intent.normalizedQuery()
                    + "\n## Question\n" + c.question();
            String answer = generation.streamGenerate(system + ResponseLanguagePolicy.forTarget(intent.language()), prompt)
                    .reduce("", String::concat).block(Duration.ofSeconds(30));
            assertThat(answer).as(c.id()).isNotBlank();
            switch (c.language()) {
                case "zh" -> assertThat(answer).containsPattern("[\\p{IsHan}]{2,}");
                case "en" -> assertThat(answer).doesNotContainPattern("[\\p{IsHan}]");
                case "es" -> assertThat(answer.toLowerCase()).contains("ingeniero");
                case "ja" -> assertThat(answer).containsPattern("[\\p{IsHiragana}\\p{IsKatakana}]{2,}");
                default -> throw new AssertionError("Missing test language assertion");
            }
            var checked = safety.checkOutputWithContext(new OutputSafetyContext(
                    c.question(), answer, "GROUNDED", List.of(), evidence), UUID.randomUUID());
            assertThat(checked.verdict()).as(c.id() + " verification: " + checked.reason()).isEqualTo(SafetyVerdict.PASS);
            System.out.printf("Language smoke: %s [%s] %s%n", c.id(), intent.language(), answer.strip());
        }
        String chineseQuestion = cases.getFirst().question();
        String wrongLanguage = "Yuqi Guo is a Software Engineer at Goldman Sachs.";
        for (int i = 0; i < 3; i++) {
            var mismatch = safety.checkOutputWithContext(new OutputSafetyContext(
                    chineseQuestion, wrongLanguage, "GROUNDED", List.of(), evidence), UUID.randomUUID());
            assertThat(mismatch.verdict()).as("Mismatch check: " + mismatch.reason()).isEqualTo(SafetyVerdict.WARN);
            assertThat(mismatch.category()).isEqualTo("LANGUAGE_MISMATCH");
        }
        var localization = new ResponseLanguageService(generation);
        String translated = localization.alignToInputLanguage(chineseQuestion, wrongLanguage);
        assertThat(translated).containsPattern("[\\p{IsHan}]{2,}");
        assertThat(localization.alignToLanguage("en", "他是一名软件工程师。")).doesNotContainPattern("[\\p{IsHan}]");
        System.out.println("Language smoke: mismatch detected and both localization directions passed");
    }

    private record Case(String id, String question, String language, String history) {}
}
