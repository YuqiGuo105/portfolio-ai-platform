package site.yuqi.agent.attachment;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import site.yuqi.agent.controller.AttachmentController;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AttachmentControllerTest {
    @Test
    void preservesHttpsWhenCloudRunTerminatesTls() throws Exception {
        AttachmentService service = mock(AttachmentService.class);
        when(service.issueUpload(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new AttachmentService.UploadGrant("test", "test.txt", "text/plain", 4, 999, "signature"));
        MockMvcBuilders.standaloneSetup(new AttachmentController(service)).build()
                .perform(post("/api/rag/attachments/upload-url").header("Host", "agent.example.test")
                        .header("X-Forwarded-Proto", "https").header("X-CW-Device-Id", "fixture")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"test\",\"name\":\"test.txt\",\"mimeType\":\"text/plain\",\"sizeBytes\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.startsWith("https://")));
    }
}
