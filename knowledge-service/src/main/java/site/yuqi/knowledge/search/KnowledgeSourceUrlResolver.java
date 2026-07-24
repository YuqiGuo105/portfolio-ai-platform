package site.yuqi.knowledge.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import site.yuqi.knowledge.model.KnowledgeChunk;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves a first-party canonical URL from indexed metadata.
 *
 * <p>New index records should carry {@code source_url}. The source-type map is
 * retained as a migration fallback so existing records remain linkable while
 * the index is rebuilt.
 */
@Component
public class KnowledgeSourceUrlResolver {

    private static final Map<String, String> SOURCE_PATHS = Map.of(
            "BLOG", "/blog-single/",
            "PROJECT", "/work-single/",
            "LIFE_BLOG", "/life-blog/");

    private final URI publicBaseUri;

    public KnowledgeSourceUrlResolver(
            @Value("${portfolio.public-base-url:https://www.yuqi.site}") String publicBaseUrl) {
        String normalized = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        this.publicBaseUri = URI.create(normalized + "/");
    }

    public String resolve(KnowledgeChunk chunk) {
        if (chunk == null) return null;

        String indexedUrl = normalizeFirstPartyUrl(chunk.sourceUrl());
        if (indexedUrl != null) return indexedUrl;

        String sourceType = clean(chunk.sourceType());
        String sourceId = clean(chunk.sourceId());
        if (sourceType == null || sourceId == null) return null;
        sourceType = sourceType.toUpperCase(Locale.ROOT);

        if ("EXPERIENCE".equals(sourceType)) {
            return publicBaseUri.resolve("#tour-background").toString();
        }
        String pathPrefix = SOURCE_PATHS.get(sourceType);
        return pathPrefix == null ? null : publicBaseUri.resolve(pathPrefix + sourceId).toString();
    }

    private String normalizeFirstPartyUrl(String raw) {
        String value = clean(raw);
        if (value == null) return null;
        try {
            URI candidate = URI.create(value);
            if (!candidate.isAbsolute()) {
                return publicBaseUri.resolve(value.startsWith("/") ? value.substring(1) : value).toString();
            }
            if (candidate.getHost() == null
                    || !candidate.getHost().equalsIgnoreCase(publicBaseUri.getHost())) return null;
            return candidate.toString();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
