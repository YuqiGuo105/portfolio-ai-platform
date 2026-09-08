package site.yuqi.agent.generation;

import org.junit.jupiter.api.Test;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSourceAccessTest {
    @Test void gatedArticleSupportsAnswersButItsExcerptNeverLeavesTheServer() {
        var hit = KnowledgeSearchResponse.ChunkHit.builder().chunkId("chunk").sourceId("2")
                .title("Travel").sourceType("LIFE_BLOG").sourceUrl("https://www.yuqi.site/life-blog/2")
                .content("Owner-approved travel facts").sourceRequiresLogin(true).build();
        var response = new KnowledgeSearchResponse("query", List.of(hit), 1);
        assertThat(AgentPipelineService.formatKnowledgeHit(hit)).contains("Owner-approved travel facts", "original article requires login");
        assertThat(AgentPipelineService.relatedLinks(response)).singleElement().satisfies(source -> {
            assertThat(source.get("sourceRequiresLogin")).isEqualTo(true);
            assertThat(source.get("snippet")).isEqualTo("");
            assertThat(source.get("url")).isEqualTo(hit.sourceUrl());
        });
        assertThat(AgentPipelineService.retrievalProvenance(response).getFirst().get("sourceRequiresLogin")).isEqualTo(true);
    }

    @Test void publicSourceKeepsItsExcerptAndLegacyConstructorRemainsCompatible() {
        var hit = new KnowledgeSearchResponse.ChunkHit("chunk", "doc", "Public", "Excerpt", 1.0,
                "BLOG", "1", "https://www.yuqi.site/blog-single/1");
        assertThat(hit.sourceRequiresLogin()).isFalse();
        assertThat(AgentPipelineService.relatedLinks(new KnowledgeSearchResponse("q", List.of(hit), 1))
                .getFirst().get("snippet")).isEqualTo("Excerpt");
    }
}
