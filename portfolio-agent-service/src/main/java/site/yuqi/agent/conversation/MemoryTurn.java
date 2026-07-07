package site.yuqi.agent.conversation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryTurn {
    private String role;
    private String content;
    private String route;
    private String targetTool;
    private Map<String, Object> toolContext;
    private Integer approxTokens;
    private Instant createdAt;
}
