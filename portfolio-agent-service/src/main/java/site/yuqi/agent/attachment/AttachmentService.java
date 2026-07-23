package site.yuqi.agent.attachment;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRegistry registry;
    private final AttachmentUploadSigner uploadSigner;
    private final SupabaseAttachmentStorage storage;

    @Value("${agent.attachments.max-files-per-conversation:2}")
    private int maxFilesPerConversation;

    @Value("${agent.attachments.max-file-bytes:5242880}")
    private long maxFileBytes;

    @Value("${agent.attachments.upload-url-ttl-seconds:300}")
    private long uploadUrlTtlSeconds;

    public UploadGrant issueUpload(String conversationId,
                                   String originalName,
                                   String suppliedMimeType,
                                   long sizeBytes) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("Conversation identity is required");
        }
        if (sizeBytes <= 0 || sizeBytes > maxFileBytes) {
            throw new IllegalArgumentException("Attachment exceeds the configured size limit");
        }
        String mimeType = AttachmentPolicy.validateAndNormalize(originalName, suppliedMimeType);
        registry.enforceIssueRate(conversationId);
        if (registry.conversationAttachmentCount(conversationId) >= maxFilesPerConversation) {
            throw new IllegalStateException("Conversation attachment limit reached");
        }

        String attachmentId = UUID.randomUUID().toString();
        String safeName = AttachmentPolicy.safeFileName(originalName);
        String objectPath = AttachmentPolicy.objectPath(attachmentId);
        AttachmentRecord record = AttachmentRecord.builder()
                .id(attachmentId)
                .conversationId(conversationId)
                .bucket(storage.bucket())
                .objectPath(objectPath)
                .originalName(safeName)
                .mimeType(mimeType)
                .declaredSizeBytes(sizeBytes)
                .status(AttachmentRecord.Status.ISSUED)
                .build();
        registry.register(record);

        AttachmentUploadSigner.SignedUpload signed =
                uploadSigner.sign(attachmentId, conversationId, uploadUrlTtlSeconds);
        return new UploadGrant(
                attachmentId,
                safeName,
                mimeType,
                sizeBytes,
                signed.expiresAtEpochSeconds(),
                signed.signature());
    }

    public AttachmentRecord acceptUpload(String attachmentId,
                                         long expiresAt,
                                         String signature,
                                         byte[] content) {
        AttachmentRecord record = registry.load(attachmentId);
        if (record == null) {
            throw new IllegalArgumentException("Attachment upload grant was not found");
        }
        if (!uploadSigner.verify(
                attachmentId, record.getConversationId(), expiresAt, signature)) {
            throw new SecurityException("Attachment upload signature is invalid or expired");
        }
        if (record.getStatus() != AttachmentRecord.Status.ISSUED) {
            throw new IllegalStateException("Attachment upload grant has already been consumed");
        }
        if (content == null || content.length == 0
                || content.length > maxFileBytes
                || content.length != record.getDeclaredSizeBytes()) {
            throw new IllegalArgumentException("Uploaded attachment size does not match its grant");
        }
        if (!AttachmentPolicy.matchesContent(record.getMimeType(), content)) {
            throw new IllegalArgumentException("Uploaded attachment content does not match its declared type");
        }

        storage.upload(record.getObjectPath(), content, record.getMimeType());
        registry.markReady(record, content.length, record.getMimeType());
        return record;
    }

    public void deleteOwned(String attachmentId, String conversationId) {
        AttachmentRecord record = registry.requireOwned(attachmentId, conversationId);
        storage.delete(record.getObjectPath());
        registry.remove(record);
    }

    public void endConversation(String conversationId) {
        registry.expireConversationNow(conversationId);
    }

    public record UploadGrant(
            String attachmentId,
            String originalName,
            String mimeType,
            long sizeBytes,
            long uploadExpiresAtEpochSeconds,
            String signature) {
    }
}
