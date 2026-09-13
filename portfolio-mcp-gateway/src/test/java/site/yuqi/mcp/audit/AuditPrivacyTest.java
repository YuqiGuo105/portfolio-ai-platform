package site.yuqi.mcp.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.mcp.model.ToolDefinition;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class AuditPrivacyTest {
    @Test void visitorQueriesLogMetadataWithoutIdentifiersOrFilters(CapturedOutput output) {
        var audit = new AuditService(new ObjectMapper());
        ReflectionTestUtils.setField(audit, "enabled", true);
        audit.logInvocation(ToolDefinition.builder().name("visitor.search_events").build(), "admin@example.invalid",
                Map.of("filter", Map.of("q", "192.0.2.10", "sessionId", "private-session")),
                null, "ok", 200, 10L, null);
        assertThat(output.getOut()).contains("visitor.search_events").doesNotContain(
                "admin@example.invalid", "192.0.2.10", "private-session");
    }

    @Test void logsNoRawCandidateDataOrCredentials(CapturedOutput output) {
        var audit=new AuditService(new ObjectMapper()); ReflectionTestUtils.setField(audit,"enabled",true);
        audit.logInvocation(ToolDefinition.builder().name("career.update_private_answers").build(),"private@example.com",
                Map.of("answers",Map.of("visa","sensitive-visa-value"),"content","private-resume-text","PASSWORD","secret-pass"),
                "private-idempotency-key","error",500,15L,"personal-error-body");
        assertThat(output.getOut()).contains("career.update_private_answers").doesNotContain("private@example.com",
                "sensitive-visa-value","private-resume-text","secret-pass","private-idempotency-key","personal-error-body");
    }
}
