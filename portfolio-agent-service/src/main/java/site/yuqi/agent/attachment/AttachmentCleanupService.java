package site.yuqi.agent.attachment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentCleanupService {
    private final AttachmentRegistry registry;
    private final SupabaseAttachmentStorage storage;

    public boolean delete(AttachmentRecord record) {
        // Persist deletion intent before I/O so a terminated request can be recovered.
        queue(record, Duration.ZERO);
        try {
            storage.delete(record.getObjectPath());
            registry.remove(record);
            return true;
        } catch (Exception e) {
            queue(record, Duration.ofMinutes(1));
            log.warn("Attachment deletion pending retry attachmentId={}", record.getId());
            return false;
        }
    }

    private void queue(AttachmentRecord record, Duration delay) {
        try {
            registry.retryDeletion(record, "Temporary attachment cleanup", delay);
        } catch (Exception e) {
            // Still attempt Storage deletion; the original expiry index is a fallback.
            log.warn("Attachment cleanup registry unavailable attachmentId={}", record.getId());
        }
    }
}
