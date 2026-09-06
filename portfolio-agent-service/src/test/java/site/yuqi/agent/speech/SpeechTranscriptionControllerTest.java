package site.yuqi.agent.speech;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import site.yuqi.agent.web.SupabaseJwtAuthFilter;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpeechTranscriptionControllerTest {
    @Test void onlyTrustedProxyCanTranscribeAndResponseIsNotCached() throws Exception {
        var service = mock(SpeechTranscriptionService.class);
        when(service.transcribe("fixture")).thenReturn("Hello, 你好。");
        var mvc = MockMvcBuilders.standaloneSetup(new SpeechTranscriptionController(service))
                .addFilters(new SupabaseJwtAuthFilter("", "test-internal", "", false, false)).build();
        mvc.perform(post("/api/chat/transcribe").contentType(MediaType.APPLICATION_JSON).content("{\"audio\":\"fixture\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        mvc.perform(post("/api/chat/transcribe").header("Authorization", "Bearer test-internal")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audio\":\"fixture\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.text").value("Hello, 你好。"));
    }
}
