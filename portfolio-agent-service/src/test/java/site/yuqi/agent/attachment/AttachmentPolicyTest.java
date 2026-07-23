package site.yuqi.agent.attachment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentPolicyTest {

    @Test
    void normalizesExtensionAndAllowsMissingBrowserMime() {
        assertThat(AttachmentPolicy.validateAndNormalize(
                "notes.md", "application/octet-stream"))
                .isEqualTo("text/markdown");
    }

    @Test
    void rejectsExtensionMimeMismatch() {
        assertThatThrownBy(() -> AttachmentPolicy.validateAndNormalize(
                "report.pdf", "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesMagicBytesBeforeStorage() {
        byte[] pdf = "%PDF-1.7\nbody".getBytes(StandardCharsets.US_ASCII);
        byte[] fakePdf = "not a pdf".getBytes(StandardCharsets.UTF_8);

        assertThat(AttachmentPolicy.matchesContent("application/pdf", pdf)).isTrue();
        assertThat(AttachmentPolicy.matchesContent("application/pdf", fakePdf)).isFalse();
        assertThat(AttachmentPolicy.matchesContent(
                "text/plain", new byte[] {'o', 'k', 0, 'x'})).isFalse();
    }
}
