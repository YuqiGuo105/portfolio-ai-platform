package site.yuqi.knowledge.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchRequest;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;
import site.yuqi.knowledge.search.HybridSearchService;

@RestController
@RequestMapping("/internal/v1/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final HybridSearchService searchService;

    @PostMapping("/search")
    public ResponseEntity<KnowledgeSearchResponse> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return ResponseEntity.ok(searchService.search(request));
    }
}
