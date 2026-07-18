package site.yuqi.agent.intent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import site.yuqi.agent.client.McpGatewayClient;
import site.yuqi.agent.model.ToolInvocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutorIdentityTest {

    @Test
    void forwardsTrustedAdminIdentityAndInjectsApplyIdempotencyKey() {
        McpGatewayClient gateway = mock(McpGatewayClient.class);
        AuditService audit = mock(AuditService.class);
        ToolExecutor executor = new ToolExecutor(gateway, audit);
        ToolDefinition tool = new ToolDefinition(
                "alerts.apply_change",
                IntentType.ALERTS_APPLY_CHANGE,
                "Apply prepared alert policy change.",
                RiskLevel.RISKY_WRITE,
                true,
                Set.of("changeId"),
                Set.of());
        IntentRequest request = IntentRequest.builder()
                .sessionId("admin-session")
                .utterance("apply the prepared alert change")
                .userEmail("admin@example.com")
                .userRoles("VIEWER,EDITOR,PUBLISHER,ADMIN")
                .build();
        IntentResult intent = new IntentResult(
                IntentType.ALERTS_APPLY_CHANGE,
                "alerts.apply_change",
                0.99,
                "en",
                "apply the prepared alert change",
                Map.of("changeId", "chg_123"),
                RiskLevel.RISKY_WRITE,
                true,
                List.of(),
                null);
        ToolInvocation gatewayResult = ToolInvocation.builder()
                .name(tool.name())
                .success(true)
                .latencyMs(10)
                .result(Map.of("success", true))
                .build();
        when(gateway.invoke(any(ToolInvocation.class))).thenReturn(Mono.just(gatewayResult));

        IntentResponse response = executor.execute(
                request, tool, Map.of("changeId", "chg_123"), intent);

        ArgumentCaptor<ToolInvocation> invocation = ArgumentCaptor.forClass(ToolInvocation.class);
        verify(gateway).invoke(invocation.capture());
        ToolInvocation captured = invocation.getValue();
        assertThat(response.getType()).isEqualTo("OK");
        assertThat(captured.getActor()).isEqualTo("admin@example.com");
        assertThat(captured.getRole()).isEqualTo("ADMIN");
        assertThat(captured.getIdempotencyKey()).isNotBlank();
        assertThat(captured.getArguments())
                .containsEntry("changeId", "chg_123")
                .containsEntry("_confirmed", true)
                .containsEntry("idempotencyKey", captured.getIdempotencyKey());
    }
}
