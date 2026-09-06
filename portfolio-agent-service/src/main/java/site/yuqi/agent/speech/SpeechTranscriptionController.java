package site.yuqi.agent.speech;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.yuqi.agent.web.AuthenticatedPrincipal;

import java.util.Map;

@RestController
@RequestMapping("/api/chat/transcribe")
public class SpeechTranscriptionController {
    private final SpeechTranscriptionService service;
    public SpeechTranscriptionController(SpeechTranscriptionService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<Map<String, String>> transcribe(@RequestBody Recording body, HttpServletRequest request) {
        if (AuthenticatedPrincipal.of(request).source() != AuthenticatedPrincipal.Source.INTERNAL_PROXY) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("text", service.transcribe(body.audio())));
    }

    public record Recording(String audio) {}
}
