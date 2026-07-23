package site.yuqi.agent.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class SupabaseAttachmentStorage {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean bucketVerified = new AtomicBoolean();

    @Value("${agent.attachments.supabase-url:${SUPABASE_URL:}}")
    private String supabaseUrl;

    @Value("${agent.attachments.service-role-key:${SUPABASE_SERVICE_ROLE_KEY:}}")
    private String serviceRoleKey;

    @Value("${agent.attachments.bucket:chat-agent-private}")
    private String bucket;

    @Value("${agent.attachments.max-file-bytes:5242880}")
    private long maxFileBytes;

    @Value("${agent.attachments.storage-timeout-seconds:20}")
    private long timeoutSeconds;

    public SupabaseAttachmentStorage(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.webClient = builder.build();
        this.objectMapper = objectMapper;
    }

    public String bucket() {
        return bucket;
    }

    public void upload(String objectPath, byte[] content, String mimeType) {
        ensurePrivateBucket();
        webClient.post()
                .uri(storageBase() + "/object/" + encode(bucket) + "/" + encodePath(objectPath))
                .headers(this::serviceHeaders)
                .header(HttpHeaders.CONTENT_TYPE, mimeType)
                .header("x-upsert", "false")
                .bodyValue(content)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }

    public byte[] download(String objectPath) {
        ensurePrivateBucket();
        byte[] bytes = webClient.get()
                .uri(storageBase() + "/object/authenticated/" + encode(bucket) + "/" + encodePath(objectPath))
                .headers(this::serviceHeaders)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
        return bytes == null ? new byte[0] : bytes;
    }

    public void delete(String objectPath) {
        ensureConfigured();
        webClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri(storageBase() + "/object/" + encode(bucket))
                .headers(this::serviceHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prefixes", List.of(objectPath)))
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();
    }

    private synchronized void ensurePrivateBucket() {
        if (bucketVerified.get()) return;
        ensureConfigured();
        String response = webClient.get()
                .uri(storageBase() + "/bucket/" + encode(bucket))
                .headers(this::serviceHeaders)
                .exchangeToMono(result -> result.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(responseBody -> {
                            if (bucketMissing(result.statusCode(), responseBody)) {
                                return createPrivateBucket();
                            }
                            if (result.statusCode().isError()) {
                                return Mono.error(new IllegalStateException(
                                        "Attachment bucket lookup failed with HTTP "
                                                + result.statusCode().value()));
                            }
                            return Mono.just(responseBody);
                        }))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        if (response != null && !response.isBlank()) {
            try {
                JsonNode bucketNode = objectMapper.readTree(response);
                if (bucketNode.path("public").asBoolean(false)) {
                    throw new IllegalStateException(
                            "Attachment bucket must be private: " + bucket);
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.debug("Storage bucket response was not JSON: {}", e.toString());
            }
        }
        bucketVerified.set(true);
    }

    boolean bucketMissing(HttpStatusCode status, String responseBody) {
        if (status == HttpStatus.NOT_FOUND) return true;
        if (status != HttpStatus.BAD_REQUEST || responseBody == null || responseBody.isBlank()) {
            return false;
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody);
            return "404".equals(error.path("statusCode").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private Mono<String> createPrivateBucket() {
        return webClient.post()
                .uri(storageBase() + "/bucket")
                .headers(this::serviceHeaders)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "id", bucket,
                        "name", bucket,
                        "public", false,
                        "file_size_limit", maxFileBytes,
                        "allowed_mime_types", AttachmentPolicy.allowedMimeTypes()))
                .retrieve()
                .bodyToMono(String.class);
    }

    private void ensureConfigured() {
        if (supabaseUrl == null || supabaseUrl.isBlank()
                || serviceRoleKey == null || serviceRoleKey.isBlank()) {
            throw new IllegalStateException("Private attachment storage is not configured");
        }
    }

    private String storageBase() {
        return supabaseUrl.replaceAll("/+$", "") + "/storage/v1";
    }

    private void serviceHeaders(HttpHeaders headers) {
        headers.set("apikey", serviceRoleKey);
        headers.setBearerAuth(serviceRoleKey);
    }

    private String encodePath(String path) {
        return java.util.Arrays.stream(path.split("/"))
                .map(this::encode)
                .collect(java.util.stream.Collectors.joining("/"));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
