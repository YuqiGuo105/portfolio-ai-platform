package site.yuqi.agent.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResponseLanguageServiceTest {

    @Test
    void alignsCandidateAnswerWithCurrentInputLanguageUsingLlm() {
        GeminiGenerationService generationService = mock(GeminiGenerationService.class);
        ResponseLanguageService service = new ResponseLanguageService(generationService);
        when(generationService.generate(anyString(), anyString()))
                .thenReturn("我可以帮你查看最近 7 天的聚合访问数据。");

        String answer = service.alignToInputLanguage(
                "最近的访客",
                "I can help review aggregate visitor data for the last 7 days.");

        assertThat(answer).isEqualTo("我可以帮你查看最近 7 天的聚合访问数据。");
        verify(generationService).generate(
                contains("translation and localization layer"),
                contains("最近的访客"));
        verify(generationService).generate(
                anyString(),
                contains("I can help review aggregate visitor data for the last 7 days."));
    }

    @Test
    void keepsOriginalAnswerWhenAlignmentFails() {
        GeminiGenerationService generationService = mock(GeminiGenerationService.class);
        ResponseLanguageService service = new ResponseLanguageService(generationService);
        when(generationService.generate(anyString(), anyString()))
                .thenThrow(new RuntimeException("model unavailable"));

        String original = "Could you clarify what you need?";

        assertThat(service.alignToInputLanguage("具体一点？", original)).isEqualTo(original);
    }

    @Test
    void skipsAlignmentWhenInputIsBlank() {
        GeminiGenerationService generationService = mock(GeminiGenerationService.class);
        ResponseLanguageService service = new ResponseLanguageService(generationService);

        assertThat(service.alignToInputLanguage(" ", "No question provided."))
                .isEqualTo("No question provided.");
    }
}
