package site.yuqi.agent.attachment;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AttachmentCleanupServiceTest {
    private final AttachmentRegistry registry = mock(AttachmentRegistry.class);
    private final SupabaseAttachmentStorage storage = mock(SupabaseAttachmentStorage.class);
    private final AttachmentCleanupService cleanup = new AttachmentCleanupService(registry, storage);
    private final AttachmentRecord record = AttachmentRecord.builder().id("test").objectPath("attachments/test/content").build();

    @Test
    void removesTheStorageObjectBeforeRemovingItsRegistryEntry() {
        assertThat(cleanup.delete(record)).isTrue();
        var order = inOrder(registry, storage);
        order.verify(registry).retryDeletion(eq(record), anyString(), eq(Duration.ZERO));
        order.verify(storage).delete(record.getObjectPath());
        order.verify(registry).remove(record);
    }

    @Test
    void retainsDeletionIntentWhenStorageFails() {
        doThrow(new IllegalStateException("unavailable")).when(storage).delete(anyString());
        assertThat(cleanup.delete(record)).isFalse();
        verify(registry).retryDeletion(eq(record), anyString(), eq(Duration.ofMinutes(1)));
        verify(registry, never()).remove(any());
    }

    @Test
    void stillDeletesStorageWhenRedisFails() {
        doThrow(new IllegalStateException("unavailable")).when(registry).retryDeletion(any(), anyString(), any());
        assertThat(cleanup.delete(record)).isTrue();
        verify(storage).delete(record.getObjectPath());
    }
}
