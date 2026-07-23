package site.yuqi.agent.attachment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentUploadSignerTest {

    private final AttachmentUploadSigner signer = new AttachmentUploadSigner(
            "test-only-signing-secret-that-is-long-enough");

    @Test
    void signedGrantIsBoundToAttachmentAndConversation() {
        AttachmentUploadSigner.SignedUpload signed =
                signer.sign("attachment-1", "conversation-1", 300);

        assertThat(signer.verify(
                "attachment-1",
                "conversation-1",
                signed.expiresAtEpochSeconds(),
                signed.signature())).isTrue();
        assertThat(signer.verify(
                "attachment-2",
                "conversation-1",
                signed.expiresAtEpochSeconds(),
                signed.signature())).isFalse();
        assertThat(signer.verify(
                "attachment-1",
                "conversation-2",
                signed.expiresAtEpochSeconds(),
                signed.signature())).isFalse();
    }

    @Test
    void expiredGrantIsRejected() {
        assertThat(signer.verify(
                "attachment-1",
                "conversation-1",
                1L,
                "invalid")).isFalse();
    }
}
