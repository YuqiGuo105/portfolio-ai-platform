package site.yuqi.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SSE envelope. ChatController emits one of these for each step
 * (intent classification, tool start, tool result, model token, final
 * answer, error). The widget renders progressively from {@code type}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStreamEvent {

    /**
     * Event types the widget recognizes:
     * <ul>
     *   <li>{@code intent}     – fast-path / planner classification</li>
     *   <li>{@code tool_start} – about to call a tool</li>
     *   <li>{@code tool_end}   – tool returned (result attached)</li>
     *   <li>{@code token}      – streamed model token</li>
     *   <li>{@code answer}     – final aggregated answer</li>
     *   <li>{@code error}      – terminal error; stream will close</li>
     *   <li>{@code done}       – stream complete (always last)</li>
     * </ul>
     */
    private String type;
    private Object payload;
}
