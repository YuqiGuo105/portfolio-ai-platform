package site.yuqi.agent.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublicProfileLinksTest {

    @Test
    void exposesConfiguredHttpsLinksInStableOrder() {
        var links = new PublicProfileLinks(
                "https://github.com/example",
                "https://leetcode.com/u/example/",
                "https://www.instagram.com/example/");

        assertThat(links.links()).extracting(PublicProfileLinks.ProfileLink::label)
                .containsExactly("GitHub", "LeetCode", "Instagram");
        assertThat(links.evidenceContext())
                .contains("https://github.com/example", "https://leetcode.com/u/example/",
                        "https://www.instagram.com/example/");
    }

    @Test
    void rejectsMalformedInsecureAndCredentialBearingUrls() {
        var links = new PublicProfileLinks(
                "http://github.com/example",
                "not a url",
                "https://user:secret@instagram.com/example");

        assertThat(links.links()).isEmpty();
        assertThat(links.evidenceContext()).isEmpty();
    }
}
