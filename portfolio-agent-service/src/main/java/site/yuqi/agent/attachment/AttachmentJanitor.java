package site.yuqi.agent.attachment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentJanitor {

    private final AttachmentRegistry registry;
    private final SupabaseAttachmentStorage storage;

    @Scheduled(fixedDelayString = "${agent.attachments.cleanup-interval-ms:60000}")
    public void deleteExpiredAttachments() {
        java.util.List<String> due;
        try {
            due = registry.dueAttachmentIds(100);
        } catch (Exception e) {
            log.debug("Attachment cleanup skipped because the registry is unavailable: {}", e.toString());
            return;
        }
        for (String attachmentId : due) {
            AttachmentRecord record = registry.load(attachmentId);
            if (record == null) {
                try {
                    storage.delete(AttachmentPolicy.objectPath(attachmentId));
                    registry.removeExpiryIndex(attachmentId);
                    log.info("Orphan attachment deleted attachmentId={}", attachmentId);
                } catch (Exception e) {
                    log.warn("Orphan attachment cleanup will retry attachmentId={}", attachmentId);
                }
                continue;
            }
            if (record.getExpiresAt() != null && record.getExpiresAt().isAfter(Instant.now())) {
                continue;
            }
            try {
                storage.delete(record.getObjectPath());
                registry.remove(record);
                log.info("Expired attachment deleted attachmentId={}", attachmentId);
            } catch (Exception e) {
                Duration retryDelay = retryDelay(record.getDeleteAttempts());
                registry.retryDeletion(record, compactError(e), retryDelay);
                log.warn("Attachment cleanup retry scheduled attachmentId={} delay={}s",
                        attachmentId, retryDelay.toSeconds());
            }
        }
    }

    private Duration retryDelay(int attempts) {
        long minutes = Math.min(360, 5L * (1L << Math.min(attempts, 6)));
        return Duration.ofMinutes(minutes);
    }

    private String compactError(Exception e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.substring(0, Math.min(value.length(), 300));
    }
}
