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
        approved.addAll(candidates.stream().filter(row -> publicDocument(row.metadata())).toList());
        var evidence = response.results().stream()
                .filter(hit -> approved.stream().anyMatch(row -> row.id().equals(hit.chunkId())))
                .map(hit -> {
                    var row = approved.stream().filter(candidate -> candidate.id().equals(hit.chunkId())).findFirst().orElseThrow();
                    if (SupabaseCurrentKnowledgeSearch.approvedAnswer(row.metadata())) return project(row);
                    boolean locked = hit.sourceRequiresLogin() || Boolean.TRUE.equals(row.metadata().get("source_requires_login"));
                    return Map.<String, Object>of("text", bounded(row.content()), "sourceType", hit.sourceType(),
                            "title", hit.title() == null ? "" : hit.title(), "sourceRequiresLogin", locked,
                            "url", locked || hit.sourceUrl() == null ? "" : hit.sourceUrl());
                }).toList();
        return Map.of("query", query, "evidence", evidence, "total", evidence.size(),
                "status", evidence.isEmpty() ? "NO_EVIDENCE" : "EVIDENCE_FOUND",
                "retrievalStrategy", response.retrievalStrategy(),
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
                && "ACTIVE".equals(meta.get("status")) && "public".equals(meta.get("answer_visibility"))
                && Boolean.TRUE.equals(meta.get("retrieval_eligible"));
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
}
