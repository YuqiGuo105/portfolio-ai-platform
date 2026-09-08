package site.yuqi.knowledge.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import site.yuqi.knowledge.search.PublicProfileEvidenceService;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1/knowledge/public")
@RequiredArgsConstructor
public class PublicEvidenceController {
    private final PublicProfileEvidenceService evidence;

    @PostMapping("/search")
    public Map<String, Object> search(@Valid @RequestBody Query request) {
        return evidence.search(request.query(), request.limit() == null ? 6 : request.limit());
    }

    @GetMapping("/profile")
    public Map<String, Object> profile() { return evidence.profile(); }

    public record Query(@NotBlank @Size(max = 300) String query, Integer limit) {}
}
