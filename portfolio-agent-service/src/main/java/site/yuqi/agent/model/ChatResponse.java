package site.yuqi.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Final aggregated answer returned alongside the SSE stream's last event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String sessionId;
    private String answer;
    private List<ToolInvocation> invokedTools;
    private String model;
    private String finishReason;
}
