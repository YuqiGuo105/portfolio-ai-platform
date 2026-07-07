package site.yuqi.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Portfolio Agent Service.
 *
 * <p>Top-level entry point for chat orchestration. Receives messages from the
 * ChatWidget, classifies intent via an LLM-first pipeline
 * ({@code site.yuqi.agent.intent.IntentOrchestrator}), executes the chosen
 * tool through the MCP gateway, and streams the result back over SSE
 * ({@code ChatController}) or returns JSON via {@code IntentController}.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AgentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServiceApplication.class, args);
    }
}
