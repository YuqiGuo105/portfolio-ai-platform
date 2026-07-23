package site.yuqi.agent.attachment;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AttachmentPolicy {

    private static final Set<String> MIME_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/webp",
            "text/plain",
            "text/markdown",
            "text/csv",
            "application/json");

    private static final Map<String, String> EXTENSION_MIME = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp",
            "txt", "text/plain",
            "md", "text/markdown",
            "csv", "text/csv",
            "json", "application/json");

    private AttachmentPolicy() {
    }

    public static List<String> allowedMimeTypes() {
        return MIME_TYPES.stream().sorted().toList();
    }

    public static boolean isTextMimeType(String mimeType) {
        return mimeType != null
                && (mimeType.startsWith("text/") || "application/json".equals(mimeType));
    }

    public static String validateAndNormalize(String fileName, String suppliedMimeType) {
        String extension = extension(fileName);
        String expected = EXTENSION_MIME.get(extension);
        String supplied = normalizeMime(suppliedMimeType);
        if (expected == null || !MIME_TYPES.contains(expected)) {
            throw new IllegalArgumentException("Unsupported attachment file type");
        }
        if (!supplied.isBlank() && !"application/octet-stream".equals(supplied)
                && !mimeCompatible(expected, supplied)) {
            throw new IllegalArgumentException("Attachment MIME type does not match its extension");
        }
        return expected;
    }

    public static boolean matchesContent(String mimeType, byte[] content) {
        if (content == null || content.length == 0) return false;
        return switch (mimeType) {
            case "application/pdf" -> startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII));
            case "image/png" -> startsWith(content,
                    new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            case "image/jpeg" -> content.length >= 3
                    && (content[0] & 0xff) == 0xff
                    && (content[1] & 0xff) == 0xd8
                    && (content[2] & 0xff) == 0xff;
            case "image/webp" -> content.length >= 12
                    && ascii(content, 0, 4).equals("RIFF")
                    && ascii(content, 8, 4).equals("WEBP");
            default -> isSafeText(content);
        };
    }

    public static String safeFileName(String original) {
        String value = original == null ? "upload" : original.trim();
        value = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-zA-Z0-9._-]+", "_")
                .replaceAll("^_+|_+$", "");
        return value.isBlank() ? "upload" : value.substring(0, Math.min(value.length(), 96));
    }

    public static String objectPath(String attachmentId) {
        if (attachmentId == null || !attachmentId.matches("[a-fA-F0-9-]{36}")) {
            throw new IllegalArgumentException("Invalid attachment identifier");
        }
        return "attachments/" + attachmentId + "/content";
    }

    private static boolean isSafeText(byte[] content) {
        int controls = 0;
        int sample = Math.min(content.length, 8192);
        for (int i = 0; i < sample; i++) {
            int b = content[i] & 0xff;
            if (b == 0) return false;
            if (b < 0x09 || (b > 0x0d && b < 0x20)) controls++;
        }
        return controls < Math.max(2, sample / 100);
    }

    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeMime(String mime) {
        if (mime == null) return "";
        return mime.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static boolean mimeCompatible(String expected, String supplied) {
        if (expected.equals(supplied)) return true;
        return "image/jpeg".equals(expected)
                && ("image/jpg".equals(supplied) || "image/pjpeg".equals(supplied));
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) return false;
        }
        return true;
    }

    private static String ascii(byte[] source, int offset, int length) {
        return new String(source, offset, length, StandardCharsets.US_ASCII);
    }
}
