package site.yuqi.agent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SupabaseAttachmentStorageTest {

    @Test
    void temporaryUploadsDoNotReceiveTheDefaultHourLongCacheLifetime() {
        java.util.List<org.springframework.web.reactive.function.client.ClientRequest> requests = new java.util.ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            return reactor.core.publisher.Mono.just(
                    org.springframework.web.reactive.function.client.ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"public\":false}").build());
        });
        SupabaseAttachmentStorage temporaryStorage = new SupabaseAttachmentStorage(builder, new ObjectMapper());
        org.springframework.test.util.ReflectionTestUtils.setField(temporaryStorage, "supabaseUrl", "https://storage.example.test");
        org.springframework.test.util.ReflectionTestUtils.setField(temporaryStorage, "serviceRoleKey", "test-only");
        org.springframework.test.util.ReflectionTestUtils.setField(temporaryStorage, "bucket", "private");
        org.springframework.test.util.ReflectionTestUtils.setField(temporaryStorage, "timeoutSeconds", 5L);
        temporaryStorage.upload("fixture/content", new byte[]{1}, "text/plain");
        assertEquals("max-age=0", requests.get(1).headers().getFirst("Cache-Control"));
        assertEquals("false", requests.get(1).headers().getFirst("x-upsert"));
    }

    private final SupabaseAttachmentStorage storage =
            new SupabaseAttachmentStorage(WebClient.builder(), new ObjectMapper());

    @Test
    void recognizesSupabaseMissingBucketEnvelope() {
        assertTrue(storage.bucketMissing(
                HttpStatus.BAD_REQUEST,
                """
                        {"statusCode":"404","error":"Bucket not found","message":"Bucket not found"}
                        """));
    }

    @Test
    void doesNotTreatOtherBadRequestsAsMissingBuckets() {
        assertFalse(storage.bucketMissing(
                HttpStatus.BAD_REQUEST,
                """
                        {"statusCode":"400","error":"Bad Request","message":"Invalid authorization"}
                        """));
        assertFalse(storage.bucketMissing(HttpStatus.BAD_REQUEST, "not-json"));
    }

    @Test
    void acceptsNativeNotFoundStatus() {
        assertTrue(storage.bucketMissing(HttpStatus.NOT_FOUND, ""));
    }
}
