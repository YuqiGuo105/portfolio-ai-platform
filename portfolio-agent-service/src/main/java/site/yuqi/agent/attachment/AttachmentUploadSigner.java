package site.yuqi.agent.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class AttachmentUploadSigner {

    private final byte[] secret;

    public AttachmentUploadSigner(
            @Value("${agent.attachments.signing-secret:${AGENT_SERVICE_INTERNAL_TOKEN:}}")
            String signingSecret) {
        String value = signingSecret == null ? "" : signingSecret.trim();
        this.secret = value.getBytes(StandardCharsets.UTF_8);
    }

    public SignedUpload sign(String attachmentId, String conversationId, long ttlSeconds) {
        requireConfigured();
        long expiresAt = Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        return new SignedUpload(expiresAt, signature(attachmentId, conversationId, expiresAt));
    }

    public boolean verify(String attachmentId,
                          String conversationId,
                          long expiresAt,
                          String candidateSignature) {
        if (secret.length == 0 || candidateSignature == null
                || Instant.now().getEpochSecond() > expiresAt) {
            return false;
        }
        byte[] expected = signature(attachmentId, conversationId, expiresAt)
                .getBytes(StandardCharsets.US_ASCII);
        byte[] actual = candidateSignature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }

    private String signature(String attachmentId, String conversationId, long expiresAt) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            String material = "attachment-upload-v1\n"
                    + attachmentId + "\n" + conversationId + "\n" + expiresAt;
            return HexFormat.of().formatHex(mac.doFinal(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign attachment upload", e);
        }
    }

    private void requireConfigured() {
        if (secret.length < 24) {
            throw new IllegalStateException("Attachment signing secret is not configured");
        }
    }

    public record SignedUpload(long expiresAtEpochSeconds, String signature) {
    }
}
