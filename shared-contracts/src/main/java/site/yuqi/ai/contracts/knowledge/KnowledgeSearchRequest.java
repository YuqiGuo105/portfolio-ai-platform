package site.yuqi.ai.contracts.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KnowledgeSearchRequest(
        String query,
        String locale,
        List<String> visibility,
        int topK
) {
    public KnowledgeSearchRequest {
        if (topK <= 0) topK = 8;
        // Omitted locale means cross-language retrieval. Answer language is a
        // presentation concern and must not hide otherwise relevant evidence.
        if (locale != null && locale.isBlank()) locale = null;
        if (visibility == null) visibility = List.of("public");
    }
}
