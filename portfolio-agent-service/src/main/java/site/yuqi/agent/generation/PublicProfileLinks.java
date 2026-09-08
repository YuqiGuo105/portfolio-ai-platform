package site.yuqi.agent.generation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class PublicProfileLinks {

    private final List<ProfileLink> links;

    public PublicProfileLinks(
            @Value("${portfolio.public-profile.github-url:${PUBLIC_GITHUB_URL:}}") String githubUrl,
            @Value("${portfolio.public-profile.leetcode-url:${PUBLIC_LEETCODE_URL:}}") String leetcodeUrl,
            @Value("${portfolio.public-profile.instagram-url:${PUBLIC_INSTAGRAM_URL:}}") String instagramUrl) {
        this.links = Stream.of(
                        profile("GitHub", githubUrl),
                        profile("LeetCode", leetcodeUrl),
                        profile("Instagram", instagramUrl))
                .filter(link -> link != null)
                .toList();
    }

    public List<ProfileLink> links() {
        return links;
    }

    public String evidenceContext() {
        if (links.isEmpty()) {
            return "";
        }
        return "## Official Public Profile Links\n"
                + "These owner-configured links are verified first-party public profile data. "
                + "Use the exact URL when the user asks for one; do not guess or rewrite it.\n"
                + links.stream()
                .map(link -> "- " + link.label() + ": " + link.url())
                .collect(Collectors.joining("\n"));
    }

    private static ProfileLink profile(String label, String value) {
        String url = validatedHttpsUrl(value);
        return url == null ? null : new ProfileLink(label, url);
    }

    static String validatedHttpsUrl(String value) {
        if (value == null || value.isBlank() || value.length() > 2048) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record ProfileLink(String label, String url) {
    }
}
