package site.yuqi.mcp.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class VisitorQueryArgumentsTest {
    @Test void defaultQueryIsBoundedAndExcludesAdmin() {
        var query = VisitorQueryArguments.normalize("visitor.search_events", Map.of());
        assertThat(query).containsEntry("size", 25).containsEntry("page", 0).containsEntry("includeAdmin", false);
        assertThat(Duration.between(Instant.parse(query.get("from").toString()), Instant.parse(query.get("to").toString())))
                .isEqualTo(Duration.ofHours(24));
    }

    @Test void sessionLookupCannotBeBroadenedToAllSessions() {
        assertThatThrownBy(() -> VisitorQueryArguments.normalize("visitor.get_session_events", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VisitorQueryArguments.normalize("visitor.get_session_events",
                Map.of("sessionId", "session-a", "filter", Map.of("sessionId", "session-b"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(VisitorQueryArguments.normalize("visitor.get_session_events", Map.of("sessionId", "session-a")))
                .containsEntry("sessionId", "session-a");
    }

    @Test void fixedWindowAndNestedFiltersAreTranslatedWithoutLosingPrecision() {
        var query = VisitorQueryArguments.normalize("visitor.search_events", Map.of(
                "filter", Map.of("country", "US", "city", "Salt Lake City", "includeAdmin", true),
                "window", Map.of("from", "2026-09-01T00:00:00.123Z", "to", "2026-09-02T00:00:00.123Z"),
                "page", Map.of("number", 2, "size", 100)));
        assertThat(query).containsEntry("country", "US").containsEntry("city", "Salt Lake City")
                .containsEntry("includeAdmin", true).containsEntry("page", 2).containsEntry("size", 100)
                .containsEntry("from", "2026-09-01T00:00:00.123Z");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"role\":\"ADMIN\"}", "{\"filter\":null}", "{\"filter\":[]}",
            "{\"filter\":{\"includeAdmin\":\"true\"}}", "{\"filter\":{\"sessionId\":\" \"}}",
            "{\"filter\":{\"q\":{\"sql\":\"select\"}}}", "{\"filter\":{\"ipAddress\":\"192.0.2.10\"}}",
            "{\"filter\":{\"q\":\"line\\nfeed\"}}", "{\"page\":{\"size\":101}}",
            "{\"page\":{\"size\":0}}", "{\"page\":{\"number\":-1}}", "{\"page\":{\"number\":10001}}",
            "{\"page\":{\"size\":1.5}}", "{\"page\":{\"size\":\"100\"}}", "{\"page\":{\"size\":1e100}}",
            "{\"window\":{\"hours\":0}}", "{\"window\":{\"hours\":745}}", "{\"window\":{\"timezone\":\"bad\"}}",
            "{\"window\":{\"from\":\"2026-09-01T00:00:00Z\"}}",
            "{\"window\":{\"from\":\"2026-09-01\",\"to\":\"2026-09-02\"}}",
            "{\"window\":{\"from\":\"2026-09-02T00:00:00Z\",\"to\":\"2026-09-01T00:00:00Z\"}}",
            "{\"window\":{\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-01T00:00:00Z\"}}",
            "{\"window\":{\"from\":\"2026-08-01T00:00:00Z\",\"to\":\"2026-09-02T00:00:00Z\"}}",
            "{\"window\":{\"from\":\"2026-09-01T00:00:00Z\",\"to\":\"2026-09-02T00:00:00Z\",\"hours\":24}}"
    })
    @SuppressWarnings("unchecked")
    void rejectsUnsupportedOrUnboundedQueries(String json) throws Exception {
        Map<String, Object> input = new ObjectMapper().readValue(json, Map.class);
        assertThatThrownBy(() -> VisitorQueryArguments.normalize("visitor.search_events", input))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
