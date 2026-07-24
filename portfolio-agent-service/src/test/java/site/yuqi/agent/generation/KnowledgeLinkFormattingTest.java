package site.yuqi.agent.generation;

import org.junit.jupiter.api.Test;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeLinkFormattingTest {

    @Test
    void formatsTrustedSourceUrlForGroundedGeneration() {
        KnowledgeSearchResponse.ChunkHit hit = hit(
                "project-1", "Portfolio Platform",
                "A production-minded distributed platform.",
                "PROJECT", "7c3715bf", "https://www.yuqi.site/work-single/7c3715bf");

        assertThat(AgentPipelineService.formatKnowledgeHit(hit))
                .contains("## Portfolio Platform")
                .contains("Source URL: https://www.yuqi.site/work-single/7c3715bf")
                .contains("production-minded distributed platform");
    }

    @Test
    void buildsVisibleRelatedContentCardsAndDeduplicatesChunkUrls() {
        KnowledgeSearchResponse response = KnowledgeSearchResponse.builder()
                .queryId("query-1")
                .latencyMs(12)
                .results(List.of(
                        hit("chunk-1", "Portfolio Platform", "First chunk",
                                "PROJECT", "project-1", "https://www.yuqi.site/work-single/project-1"),
                        hit("chunk-2", "Portfolio Platform", "Second chunk",
                                "PROJECT", "project-1", "https://www.yuqi.site/work-single/project-1"),
                        hit("chunk-3", "Architecture Notes", "Article chunk",
                                "BLOG", "blog-1", "https://www.yuqi.site/blog-single/blog-1")))
                .build();

        List<Map<String, Object>> links = AgentPipelineService.relatedLinks(response);

        assertThat(links).hasSize(2);
        assertThat(links.get(0))
                .containsEntry("type", "project")
                .containsEntry("title", "Portfolio Platform")
                .containsEntry("url", "https://www.yuqi.site/work-single/project-1");
        assertThat(links.get(1)).containsEntry("type", "blog");
    }

    private static KnowledgeSearchResponse.ChunkHit hit(
            String chunkId,
            String title,
            String content,
            String sourceType,
            String sourceId,
            String sourceUrl) {
        return KnowledgeSearchResponse.ChunkHit.builder()
                .chunkId(chunkId)
                .documentId("doc-" + sourceId)
                .title(title)
                .content(content)
                .score(0.9)
                .sourceType(sourceType)
                .sourceId(sourceId)
                .sourceUrl(sourceUrl)
                .build();
    }
}
