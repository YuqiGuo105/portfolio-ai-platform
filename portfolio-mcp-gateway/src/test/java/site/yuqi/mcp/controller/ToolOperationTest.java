package site.yuqi.mcp.controller;

import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import site.yuqi.mcp.adapter.*;
import site.yuqi.mcp.audit.AuditService;
import site.yuqi.mcp.idempotency.IdempotencyKeyService;
import site.yuqi.mcp.model.*;
import site.yuqi.mcp.registry.ToolRegistry;
import site.yuqi.mcp.security.*;
import site.yuqi.mcp.validation.ParameterValidator;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ToolOperationTest {
    ToolController controller;
    IdempotencyKeyService ledger;
    DomainServiceAdapter adapter;
    Map<String,Object> claim;
    @BeforeEach void setup() {
        var registry=mock(ToolRegistry.class);
        var validator=mock(ParameterValidator.class);
        var privacy=mock(AnalyticsPrivacyPolicy.class);
        var risk=mock(RiskGateValidator.class);
        var resolver=mock(AdapterResolver.class);
        ledger=mock(IdempotencyKeyService.class); adapter=mock(DomainServiceAdapter.class);
        ToolDefinition tool=ToolDefinition.builder().name("test.write").mode(ToolMode.WRITE).requiredRole("ADMIN")
                .endpoint(new ToolDefinition.Endpoint("admin","POST","/write")).build();
        when(registry.find("test.write")).thenReturn(Optional.of(tool));
        when(validator.validate(any(),any())).thenReturn(new ParameterValidator.ValidationResult(true,List.of()));
        when(privacy.check(any(),any())).thenReturn(AnalyticsPrivacyPolicy.Outcome.ok());
        when(risk.check(any(),any())).thenReturn(RiskGateValidator.Outcome.ok());
        when(resolver.find("admin")).thenReturn(Optional.of(adapter));
        claim=Map.of("operationId","op-1","dispatch",true,"leaseToken","lease-1");
        when(ledger.claim(any(),any(),any(),any())).thenReturn(claim);
        when(ledger.complete(any(),any(),any(),anyInt(),any())).thenAnswer(i -> Map.of("operationId","op-1","state",i.getArgument(2)));
        controller=new ToolController(registry,validator,privacy,risk,ledger,resolver,mock(AuditService.class));
        ReflectionTestUtils.setField(controller,"internalToken","secret");
    }
    org.springframework.http.ResponseEntity<?> invoke() {
        return controller.invoke("test.write",Map.of("value","original"),"Bearer secret","intent-123","actor","ADMIN","claude","model");
    }
    @Test void duplicateReturnsPersistedResultWithoutDispatch() {
        when(ledger.claim(any(),any(),any(),any())).thenReturn(Map.of("operationId","op-1","dispatch",false,"state","SUCCEEDED","httpStatus",200,"response",Map.of("version",7)));
        assertThat(invoke().getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(adapter);
    }
    @Test void ledgerFailurePreventsDownstreamWrites() {
        when(ledger.claim(any(),any(),any(),any())).thenThrow(new IdempotencyKeyService.LedgerException("unavailable",503));
        assertThat(invoke().getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(adapter);
    }
    @Test void timeoutIsUnknownAndNeverReportedSafeToRetry() {
        when(adapter.invoke(any(),any())).thenThrow(new AdapterException("timeout",new RuntimeException()));
        Map<?,?> body=(Map<?,?>)invoke().getBody();
        assertThat(body.get("safeToRetry")).isEqualTo(false);
        assertThat(body.get("ambiguousOutcome")).isEqualTo(true);
        verify(ledger).complete(eq("actor"),eq(claim),eq("UNKNOWN"),eq(502),any());
    }
    @Test void bulkheadRejectionIsSafeToRetryBecauseNothingWasDispatched() {
        when(adapter.invoke(any(),any())).thenThrow(AdapterException.unavailableBeforeDispatch("busy"));
        Map<?,?> body=(Map<?,?>)invoke().getBody();
        assertThat(body.get("safeToRetry")).isEqualTo(true);
        verify(ledger).complete(eq("actor"),eq(claim),eq("RETRYABLE"),eq(503),any());
    }
    @Test void successfulWriteForwardsKeyAndOperationId() {
        when(adapter.invoke(any(),any())).thenReturn(Map.of("version",7));
        assertThat(invoke().getStatusCode().value()).isEqualTo(200);
        verify(adapter).invoke(any(),argThat(a -> "intent-123".equals(a.get("_idempotencyKey")) && "op-1".equals(a.get("_operationId"))));
    }
}
