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
        if (locale == null) locale = "en-US";
        if (visibility == null) visibility = List.of("public");
    }
}
