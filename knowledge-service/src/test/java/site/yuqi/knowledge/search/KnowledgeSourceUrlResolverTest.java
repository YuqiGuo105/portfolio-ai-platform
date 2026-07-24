package site.yuqi.knowledge.search;

import org.junit.jupiter.api.Test;
import site.yuqi.knowledge.model.KnowledgeChunk;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSourceUrlResolverTest {

    private final KnowledgeSourceUrlResolver resolver =
            new KnowledgeSourceUrlResolver("https://www.yuqi.site");

    @Test
    void prefersIndexedFirstPartyUrl() {
        KnowledgeChunk chunk = chunk("BLOG", "blog-1", "/blog-single/blog-1");

        assertThat(resolver.resolve(chunk))
                .isEqualTo("https://www.yuqi.site/blog-single/blog-1");
    }

    @Test
    void derivesCanonicalUrlForExistingIndexRecords() {
        KnowledgeChunk chunk = chunk("project", "project-1", null);

        assertThat(resolver.resolve(chunk))
                .isEqualTo("https://www.yuqi.site/work-single/project-1");
    }

    @Test
    void rejectsExternalIndexedUrlAndUsesFirstPartyFallback() {
        KnowledgeChunk chunk = chunk(
                "LIFE_BLOG", "42", "https://untrusted.example/private");

        assertThat(resolver.resolve(chunk))
                .isEqualTo("https://www.yuqi.site/life-blog/42");
    }

    private static KnowledgeChunk chunk(String sourceType, String sourceId, String sourceUrl) {
        return KnowledgeChunk.builder()
                .chunkId("chunk-1")
                .documentId("doc-1")
                .sourceType(sourceType)
                .sourceId(sourceId)
                .sourceUrl(sourceUrl)
                .title("Title")
                .content("Content")
                .build();
    }
}
