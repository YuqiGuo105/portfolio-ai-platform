package site.yuqi.knowledge.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import site.yuqi.knowledge.ingestion.IngestionService;
import site.yuqi.knowledge.ingestion.IngestionService.IngestRequest;

import java.util.Map;

@RestController
@RequestMapping("/internal/v1/knowledge")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody IngestRequest request) {
        int chunks = ingestionService.ingest(request);
        return ResponseEntity.ok(Map.of(
                "documentId", request.documentId(),
                "chunksIndexed", chunks,
                "status", "ok"
        ));
    }
}
