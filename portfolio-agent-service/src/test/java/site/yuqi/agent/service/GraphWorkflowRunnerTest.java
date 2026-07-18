package site.yuqi.agent.service;

import org.junit.jupiter.api.Test;
import site.yuqi.agent.budget.BudgetDecision;
import site.yuqi.agent.budget.ChatBudgetService;
import site.yuqi.agent.conversation.ConversationContextLoader;
import site.yuqi.agent.conversation.MemoryWriter;
import site.yuqi.agent.intent.IntentOrchestrator;
import site.yuqi.agent.model.ChatRequest;
import site.yuqi.agent.model.ChatStreamEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphWorkflowRunnerTest {

    @Test
    void deniedBudgetStopsBeforeConversationLoadAndIntentClassification() {
        IntentOrchestrator orchestrator = mock(IntentOrchestrator.class);
        ConversationContextLoader contextLoader = mock(ConversationContextLoader.class);
        MemoryWriter memoryWriter = mock(MemoryWriter.class);
        ChatBudgetService budgetService = mock(ChatBudgetService.class);
        Instant resetAt = Instant.parse("2026-07-19T00:00:00Z");
        when(budgetService.reserveChatRequest()).thenReturn(BudgetDecision.denied(
                "daily_budget_exhausted",
                new BigDecimal("0.75"),
                new BigDecimal("0.75"),
                BigDecimal.ZERO,
                new BigDecimal("0.05"),
                resetAt));

        GraphWorkflowRunner runner = new GraphWorkflowRunner(
                orchestrator, contextLoader, memoryWriter, budgetService);
        ChatRequest request = new ChatRequest();
        request.setSessionId("budget-test");
        request.setConversationId("conversation-test");
        request.setMessages(List.of(new ChatRequest.Message("user", "A general request")));

        List<ChatStreamEvent> events = runner.run(request).collectList().block();

        assertThat(events).isNotNull();
        assertThat(events).extracting(ChatStreamEvent::getType)
                .containsExactly("intent", "error", "done");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) events.get(1).getPayload();
        assertThat(payload)
                .containsEntry("code", "CHAT_BUDGET_UNAVAILABLE")
                .containsEntry("resetAt", resetAt.toString());
        verify(contextLoader, never()).load(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(orchestrator, never()).handle(org.mockito.ArgumentMatchers.any());
        verify(memoryWriter, never()).writeTurnPair(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.nullable(site.yuqi.agent.intent.IntentResponse.class));
    }
}
