package site.yuqi.agent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import site.yuqi.agent.attachment.AttachmentRecord;
import site.yuqi.agent.attachment.AttachmentService;
import site.yuqi.agent.conversation.ConversationKey;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/rag/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping("/upload-url")
    public ResponseEntity<Map<String, Object>> issueUpload(
            @Valid @RequestBody UploadRequest body,
            HttpServletRequest request) {
        String conversationId = ConversationKey.derive(request, body.getSessionId());
        AttachmentService.UploadGrant grant = attachmentService.issueUpload(
                conversationId, body.getName(), body.getMimeType(), body.getSizeBytes());
        URI uploadUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/rag/attachments/{id}/content")
                .queryParam("expires", grant.uploadExpiresAtEpochSeconds())
                .queryParam("signature", grant.signature())
                .buildAndExpand(grant.attachmentId())
                .toUri();
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "attachmentId", grant.attachmentId(),
                "name", grant.originalName(),
                "mimeType", grant.mimeType(),
                "sizeBytes", grant.sizeBytes(),
                "uploadUrl", uploadUrl.toString(),
                "uploadExpiresAt", Instant.ofEpochSecond(grant.uploadExpiresAtEpochSeconds()).toString()));
    }

    @PutMapping("/{attachmentId}/content")
    public ResponseEntity<Map<String, Object>> upload(
            @PathVariable String attachmentId,
            @RequestParam long expires,
            @RequestParam String signature,
            @RequestBody byte[] content) {
        AttachmentRecord record =
                attachmentService.acceptUpload(attachmentId, expires, signature, content);
        return ResponseEntity.ok(Map.of(
                "attachmentId", record.getId(),
                "status", record.getStatus().name(),
                "name", record.getOriginalName(),
                "mimeType", record.getMimeType(),
                "sizeBytes", record.getStoredSizeBytes()));
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(
            @PathVariable String attachmentId,
            @RequestParam String sessionId,
            HttpServletRequest request) {
        attachmentService.deleteOwned(
                attachmentId, ConversationKey.derive(request, sessionId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/conversation/end")
    public ResponseEntity<Void> endConversation(
            @Valid @RequestBody EndConversationRequest body,
            HttpServletRequest request) {
        attachmentService.endConversation(
                ConversationKey.derive(request, body.getSessionId()));
        return ResponseEntity.accepted().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, String>> unauthorized(SecurityException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @Data
    public static class UploadRequest {
        @NotBlank
        private String sessionId;
        @NotBlank
        private String name;
        @NotBlank
        private String mimeType;
        @Positive
        private long sizeBytes;
    }

    @Data
    public static class EndConversationRequest {
        @NotBlank
        private String sessionId;
    }
}
