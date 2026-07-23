package site.yuqi.agent.attachment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentJanitorTest {

    @Test
    void deletesOrphanFromDeterministicPathWhenLeaseRecordIsGone() {
        AttachmentRegistry registry = mock(AttachmentRegistry.class);
        SupabaseAttachmentStorage storage = mock(SupabaseAttachmentStorage.class);
        AttachmentJanitor janitor = new AttachmentJanitor(registry, storage);
        String attachmentId = UUID.randomUUID().toString();
        when(registry.dueAttachmentIds(100)).thenReturn(List.of(attachmentId));
        when(registry.load(attachmentId)).thenReturn(null);

        janitor.deleteExpiredAttachments();

        verify(storage).delete("attachments/" + attachmentId + "/content");
        verify(registry).removeExpiryIndex(attachmentId);
    }
}
