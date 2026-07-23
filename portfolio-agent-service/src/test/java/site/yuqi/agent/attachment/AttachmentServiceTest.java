package site.yuqi.agent.attachment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentServiceTest {

    private AttachmentRegistry registry;
    private AttachmentUploadSigner signer;
    private SupabaseAttachmentStorage storage;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        registry = mock(AttachmentRegistry.class);
        signer = mock(AttachmentUploadSigner.class);
        storage = mock(SupabaseAttachmentStorage.class);
        service = new AttachmentService(registry, signer, storage);
        ReflectionTestUtils.setField(service, "maxFilesPerConversation", 2);
        ReflectionTestUtils.setField(service, "maxFileBytes", 5L * 1024 * 1024);
        ReflectionTestUtils.setField(service, "uploadUrlTtlSeconds", 300L);
        when(storage.bucket()).thenReturn("private-bucket");
        when(signer.sign(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("conv-1"),
                org.mockito.ArgumentMatchers.eq(300L)))
                .thenReturn(new AttachmentUploadSigner.SignedUpload(999L, "sig"));
    }

    @Test
    void issuesOpaqueGrantAndRegistersPrivateObject() {
        AttachmentService.UploadGrant grant =
                service.issueUpload("conv-1", "../../resume.pdf", "application/pdf", 12);

        assertThat(grant.attachmentId()).isNotBlank();
        assertThat(grant.signature()).isEqualTo("sig");
        ArgumentCaptor<AttachmentRecord> record = ArgumentCaptor.forClass(AttachmentRecord.class);
        verify(registry).register(record.capture());
        assertThat(record.getValue().getConversationId()).isEqualTo("conv-1");
        assertThat(record.getValue().getBucket()).isEqualTo("private-bucket");
        assertThat(record.getValue().getObjectPath())
                .isEqualTo("attachments/" + grant.attachmentId() + "/content");
        assertThat(record.getValue().getOriginalName()).isEqualTo(".._.._resume.pdf");
        assertThat(record.getValue().getStatus()).isEqualTo(AttachmentRecord.Status.ISSUED);
    }

    @Test
    void rejectsSpoofedContentBeforePrivateStorageWrite() {
        byte[] fakePdf = "not a pdf".getBytes(StandardCharsets.UTF_8);
        AttachmentRecord record = AttachmentRecord.builder()
                .id("attachment-1")
                .conversationId("conv-1")
                .objectPath("conversations/conv-1/attachment-1/resume.pdf")
                .mimeType("application/pdf")
                .declaredSizeBytes(fakePdf.length)
                .status(AttachmentRecord.Status.ISSUED)
                .build();
        when(registry.load("attachment-1")).thenReturn(record);
        when(signer.verify("attachment-1", "conv-1", 999L, "sig")).thenReturn(true);

        assertThatThrownBy(() ->
                service.acceptUpload("attachment-1", 999L, "sig", fakePdf))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declared type");
        verify(storage, never()).upload(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
