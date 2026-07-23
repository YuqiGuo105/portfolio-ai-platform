package site.yuqi.agent.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.conversation.MemorySanitizer;
import site.yuqi.agent.conversation.RedisConversationStore;
import site.yuqi.agent.generation.GeminiGenerationService;
import site.yuqi.agent.model.AgentStreamRequest;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentContextServiceTest {

    private AttachmentRegistry registry;
    private SupabaseAttachmentStorage storage;
    private GeminiGenerationService generationService;
    private ChatBudgetService budgetService;
    private RedisConversationStore conversationStore;
    private AttachmentContextService service;

    @BeforeEach
    void setUp() {
        registry = mock(AttachmentRegistry.class);
        storage = mock(SupabaseAttachmentStorage.class);
        generationService = mock(GeminiGenerationService.class);
        budgetService = mock(ChatBudgetService.class);
        conversationStore = mock(RedisConversationStore.class);
        service = new AttachmentContextService(
                registry,
                storage,
                generationService,
                budgetService,
                conversationStore,
                new MemorySanitizer());
        ReflectionTestUtils.setField(service, "maxFilesPerRequest", 2);
        ReflectionTestUtils.setField(service, "maxTotalBytes", 8L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "maxContextChars", 6000);
        ReflectionTestUtils.setField(service, "maxParserTextChars", 200000);
    }

    @Test
    void parsesWithUtilityModelAndStoresOnlyBoundedContextInRedis() {
        byte[] content = "Role: Backend Engineer\nImpact: reduced latency 40%."
                .getBytes(StandardCharsets.UTF_8);
        AttachmentRecord record = AttachmentRecord.builder()
                .id("attachment-1")
                .conversationId("conv-1")
                .originalName("resume.txt")
                .objectPath("conversations/conv-1/attachment-1/resume.txt")
                .mimeType("text/plain")
                .storedSizeBytes(content.length)
                .status(AttachmentRecord.Status.READY)
                .build();
        when(registry.requireOwned("attachment-1", "conv-1")).thenReturn(record);
        when(storage.download(record.getObjectPath())).thenReturn(content);
        when(generationService.utilityModel()).thenReturn("gemini-2.5-flash-lite");
        when(generationService.generateWithDocuments(anyString(), anyString(), anyList()))
                .thenReturn("Confirmed: Backend Engineer; measured latency reduction: 40%.");

        AttachmentContextService.AttachmentContext result = service.resolve(
                "conv-1",
                "Please summarize my resume",
                List.of(new AgentStreamRequest.AttachmentReference("attachment-1", "resume.txt",
                        "text/plain", (long) content.length)));

        assertThat(result.hasContent()).isTrue();
        assertThat(result.cacheHit()).isFalse();
        verify(budgetService).recordModelCall("gemini-2.5-flash-lite", false, false);
        verify(registry).markParsed(eq(record), anyString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(conversationStore).mergeStructuredState(
                eq("conv-1"), eq("attachmentContext"), state.capture());
        assertThat(state.getValue()).containsKeys("files", "context", "updatedAt");
        assertThat(String.valueOf(state.getValue().get("context"))).doesNotContain(
                "access token", "private bucket");
    }
}
