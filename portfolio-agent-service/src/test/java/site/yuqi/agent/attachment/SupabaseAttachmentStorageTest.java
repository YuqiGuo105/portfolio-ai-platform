package site.yuqi.agent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupabaseAttachmentStorageTest {

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
