package site.yuqi.agent.speech;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import site.yuqi.agent.budget.BudgetDecision;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.generation.GeminiGenerationService;

import javax.sound.sampled.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpeechTranscriptionServiceTest {
    private final GeminiGenerationService generation = mock(GeminiGenerationService.class);
    private final ChatBudgetService budget = mock(ChatBudgetService.class);
    private final SpeechTranscriptionService service = new SpeechTranscriptionService(generation, budget, new ObjectMapper());

    private String audio(int sampleRate, int seconds) throws Exception {
        byte[] pcm = new byte[sampleRate * seconds * 2];
        var stream = new AudioInputStream(new ByteArrayInputStream(pcm), new AudioFormat(sampleRate, 16, 1, true, false), sampleRate * seconds);
        var output = new ByteArrayOutputStream();
        AudioSystem.write(stream, AudioFileFormat.Type.WAVE, output);
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private void allow() {
        when(budget.reserveTranscriptionRequest()).thenReturn(BudgetDecision.allowed(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN, Instant.now()));
    }

    @Test void preservesMixedLanguageWithoutBrowserLocaleAndErasesAudio() throws Exception {
        allow();
        when(generation.speechModel()).thenReturn("speech-model");
        final byte[][] observed = new byte[1][];
        when(generation.transcribeAudio(anyString(), any())).thenAnswer(call -> {
            observed[0] = call.getArgument(1);
            assertTrue(((String) call.getArgument(0)).contains("Do not translate"));
            assertTrue(observed[0][0] != 0);
            return "{\"text\":\"请解释 Kafka consumer lag。\"}";
        });
        assertEquals("请解释 Kafka consumer lag。", service.transcribe(audio(16000, 1)));
        verify(budget).recordModelCall("speech-model", false, false);
        for (byte value : observed[0]) assertEquals(0, value);
    }

    @Test void rejectsInvalidOversizedAndWrongFormatBeforeAnyModelCall() throws Exception {
        assertThrows(ResponseStatusException.class, () -> service.transcribe("!invalid"));
        assertThrows(ResponseStatusException.class, () -> service.transcribe(audio(8000, 1)));
        assertThrows(ResponseStatusException.class, () -> service.transcribe(audio(16000, 61)));
        verifyNoInteractions(generation, budget);
    }

    @Test void budgetFailureNeverCallsModel() throws Exception {
        when(budget.reserveTranscriptionRequest()).thenReturn(BudgetDecision.denied("limit", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, Instant.now()));
        assertEquals(429, assertThrows(ResponseStatusException.class, () -> service.transcribe(audio(16000, 1))).getStatusCode().value());
        verifyNoInteractions(generation);
    }

    @Test void returnsEmptyForNoSpeechAndRejectsMalformedModelOutput() throws Exception {
        allow();
        when(generation.transcribeAudio(anyString(), any())).thenReturn("{\"text\":\"\"}", "not json");
        assertEquals("", service.transcribe(audio(16000, 1)));
        assertEquals(502, assertThrows(ResponseStatusException.class, () -> service.transcribe(audio(16000, 1))).getStatusCode().value());
    }
}
