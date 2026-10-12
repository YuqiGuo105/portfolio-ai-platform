package site.yuqi.knowledge.search;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import site.yuqi.ai.contracts.knowledge.KnowledgeSearchResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Published content and explicitly approved public answers. No CV or career-vault access. */
@Service
@RequiredArgsConstructor
public class PublicProfileEvidenceService {
    private final WebClient.Builder builder;
    private final SupabaseCurrentKnowledgeSearch search;
    @Value("${knowledge.supabase.url:}") private String baseUrl;
    @Value("${knowledge.supabase.service-key:}") private String serviceKey;

    public Map<String, Object> search(String query, int limit) {
        requireCurrentStore();
        var response = search.search(query, Math.max(1, Math.min(limit, 8)));
        // Re-read source flags: the legacy search RPC fills absent visibility fields for old records.
        var candidates = rowsByIds(response.results().stream().map(KnowledgeSearchResponse.ChunkHit::chunkId).toList());
        var approved = new java.util.ArrayList<>(verified(candidates));
        var documents = candidates.stream().filter(row -> publicDocument(row.metadata())).toList();
        var publicSources = currentPublicSources(documents);
        approved.addAll(documents.stream().filter(row -> publicSources.contains(sourceKey(row.metadata()))).toList());
        var evidence = response.results().stream()
                .filter(hit -> approved.stream().anyMatch(row -> row.id().equals(hit.chunkId())))
                .map(hit -> {
                    var row = approved.stream().filter(candidate -> candidate.id().equals(hit.chunkId())).findFirst().orElseThrow();
                    if (SupabaseCurrentKnowledgeSearch.approvedAnswer(row.metadata())) return project(row);
                    return Map.<String, Object>of("text", bounded(row.content()), "sourceType", hit.sourceType(),
                            "sourceId", row.metadata().get("source_id"), "accessVerified", true,
                            "title", hit.title() == null ? "" : hit.title(), "sourceRequiresLogin", false,
                            "url", canonicalUrl(row.metadata()));
                }).toList();
        return Map.of("query", query, "evidence", evidence, "total", evidence.size(),
                "status", evidence.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE_FOUND",
                "retrievalStrategy", response.retrievalStrategy() == null ? "current_knowledge" : response.retrievalStrategy(),
                "guidance", "Use only passages that support the question; similarity is not proof. These are published sources or owner-approved public answers. A missing match is not evidence that an event never happened. Do not share restricted source links; those records require login.");
    }

    public Map<String, Object> profile() {
        requireCurrentStore();
        var rows = builder.clone().baseUrl(baseUrl).build().get()
                .uri(uri -> uri.path("/rest/v1/kb_documents").queryParam("select", "id,content,metadata")
                        .queryParam("metadata->>source_type", "eq.PROFILE")
                        .queryParam("metadata->>status", "eq.ACTIVE")
                        .queryParam("metadata->>answer_visibility", "eq.public")
                        .queryParam("metadata->>evidence_review", "eq.approved")
                        .queryParam("metadata->>retrieval_eligible", "eq.true")
                        .queryParam("order", "created_at.desc,id.asc").queryParam("limit", "20").build())
                .header("apikey", serviceKey).header("Authorization", "Bearer " + serviceKey)
                .retrieve().bodyToFlux(ProfileRow.class).collectList().timeout(Duration.ofSeconds(5)).block();
        var evidence = verified(rows == null ? List.of() : rows).stream()
                .filter(row -> "PROFILE".equals(row.metadata() == null ? null : row.metadata().get("source_type")))
                .filter(row -> SupabaseCurrentKnowledgeSearch.approvedAnswer(row.metadata()))
                .map(PublicProfileEvidenceService::project).toList();
        return Map.of("profileEvidence", evidence,
                "status", evidence.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE_FOUND",
                "guidance", "Read profileEvidence for education and other established facts. Missing structured fields are not missing qualifications. Do not invent degrees, dates, skills or pronouns.");
    }

    static Map<String, Object> project(ProfileRow row) {
        // Public answer text does not make the underlying source record publicly accessible.
        return Map.of("text", bounded(row.content()), "sourceType", row.metadata().get("source_type"),
                "sourceRequiresLogin", true, "review", "approved");
    }

    static boolean publicDocument(Map<String, Object> meta) {
        return meta != null && List.of("BLOG", "LIFE_BLOG", "PROJECT", "EXPERIENCE").contains(meta.get("source_type"))
                && "ACTIVE".equals(meta.get("status"))
                && ("public".equals(meta.get("answer_visibility"))
                    || (meta.get("answer_visibility") == null && meta.get("source_version") != null))
                && (Boolean.TRUE.equals(meta.get("retrieval_eligible"))
                    || (meta.get("retrieval_eligible") == null && meta.get("source_version") != null));
    }

    private static String sourceKey(Map<String, Object> meta) {
        return meta.get("source_type") + ":" + meta.get("source_id");
    }

    private static String canonicalUrl(Map<String, Object> meta) {
        return "https://www.yuqi.site/" + switch (String.valueOf(meta.get("source_type"))) {
            case "PROJECT" -> "work-single/" + meta.get("source_id");
            case "BLOG" -> "blog-single/" + meta.get("source_id");
            case "LIFE_BLOG" -> "life-blog/" + meta.get("source_id");
            default -> "cv";
        };
    }

    private java.util.Set<String> currentPublicSources(List<ProfileRow> documents) {
        // The content tables, not copied vector metadata, own access decisions.
        var tables = Map.of("PROJECT", "Projects", "BLOG", "Blogs", "LIFE_BLOG", "life_blogs", "EXPERIENCE", "experience");
        return reactor.core.publisher.Flux.fromIterable(tables.entrySet()).flatMap(entry -> {
            var type = entry.getKey();
            var ids = documents.stream().filter(row -> type.equals(row.metadata().get("source_type")))
                    .map(row -> String.valueOf(row.metadata().get("source_id")))
                    .filter(id -> type.equals("LIFE_BLOG") || type.equals("EXPERIENCE")
                            ? id.matches("[0-9]{1,18}") : id.matches("[a-fA-F0-9-]{36}"))
                    .distinct().limit(20).toList();
            if (ids.isEmpty()) return reactor.core.publisher.Flux.<String>empty();
            var columns = "id" + (type.equals("LIFE_BLOG") ? ",require_login" : type.equals("PROJECT") ? ",publication_status" : "");
            return builder.clone().baseUrl(baseUrl).build().get()
                    .uri(uri -> uri.path("/rest/v1/" + entry.getValue()).queryParam("select", columns)
                            .queryParam("id", "in.(" + String.join(",", ids) + ")").build())
                    .header("apikey", serviceKey).header("Authorization", "Bearer " + serviceKey)
                    .retrieve().bodyToFlux(SourceAccess.class)
                    .filter(row -> !type.equals("LIFE_BLOG") || Boolean.FALSE.equals(row.require_login()))
                    .filter(row -> !type.equals("PROJECT") || "PUBLISHED".equals(row.publication_status()))
                    .filter(row -> ids.contains(row.id()))
                    .map(row -> type + ":" + row.id());
        }, 4).collect(java.util.stream.Collectors.toSet()).timeout(Duration.ofSeconds(3)).block();
    }

    private void requireCurrentStore() {
        if (!search.isEnabled() || baseUrl == null || baseUrl.isBlank() || serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("Current public evidence is not configured");
        }
    }

    private List<ProfileRow> rowsByIds(List<String> ids) {
        var safeIds = ids.stream().filter(java.util.Objects::nonNull).map(UUID::fromString)
                .map(UUID::toString).distinct().limit(20).toList();
        if (safeIds.isEmpty()) return List.of();
        var rows = builder.clone().baseUrl(baseUrl).build().get()
                .uri(uri -> uri.path("/rest/v1/kb_documents").queryParam("select", "id,content,metadata")
                        .queryParam("id", "in.(" + String.join(",", safeIds) + ")").build())
                .header("apikey", serviceKey).header("Authorization", "Bearer " + serviceKey)
                .retrieve().bodyToFlux(ProfileRow.class).collectList().timeout(Duration.ofSeconds(3)).block();
        return rows == null ? List.of() : rows;
    }

    private List<ProfileRow> verified(List<ProfileRow> rows) {
        var eligible = rows.stream().filter(row -> SupabaseCurrentKnowledgeSearch.approvedAnswer(row.metadata())).toList();
        var originals = rowsByIds(eligible.stream().map(row -> (String) row.metadata().get("source_id")).toList());
        return eligible.stream().filter(row -> originals.stream().anyMatch(original ->
                original.id().equals(row.metadata().get("source_id")) && original.metadata() != null
                && ("personal_profile".equals(original.metadata().get("source")) || "chat_qa".equals(original.metadata().get("type")))
                && md5(original.content()).equals(row.metadata().get("original_content_md5"))))
                .toList();
    }

    private static String md5(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String bounded(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 8000));
    }

    record ProfileRow(String id, String content, Map<String, Object> metadata) {}
    record SourceAccess(String id, Boolean require_login, String publication_status) {}
}
