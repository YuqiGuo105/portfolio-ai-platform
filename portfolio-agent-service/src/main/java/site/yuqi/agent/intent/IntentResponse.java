package site.yuqi.agent.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Tagged result returned by {@link IntentOrchestrator}. The {@link #type}
 * field tells the controller which payload fields to read.
 *
 * <p>Possible values for {@link #type}:
 * <ul>
 *   <li>{@code OK}                    – tool executed; see {@link #result}</li>
 *   <li>{@code ASK}                   – clarification needed; see {@link #message} and optional {@link #options}</li>
 *   <li>{@code CONFIRMATION_REQUIRED} – write tool staged; see {@link #message} + {@link #pendingActionId}</li>
 *   <li>{@code FORBIDDEN}             – policy denied; see {@link #message}</li>
 *   <li>{@code ERROR}                 – validator/executor failure; see {@link #message}</li>
 *   <li>{@code GENERAL_CHAT}          – fall back to plain LLM chat answer; see {@link #message}</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResponse {

    private String type;
    private String message;
    private Object result;
    private List<Map<String, Object>> options;
    private String pendingActionId;

    /** Echo of the intent classification for debugging / audit / UI hints. */
    private IntentResult intent;

    // ── Factories ────────────────────────────────────────────────────────

    public static IntentResponse ok(IntentResult intent, Object result) {
        return IntentResponse.builder().type("OK").intent(intent).result(result).build();
    }

    public static IntentResponse ask(String message) {
        return IntentResponse.builder().type("ASK").message(message).build();
    }

    public static IntentResponse ask(String message, List<Map<String, Object>> options) {
        return IntentResponse.builder().type("ASK").message(message).options(options).build();
    }

    public static IntentResponse confirmation(String message, String pendingActionId, IntentResult intent) {
        return IntentResponse.builder()
                .type("CONFIRMATION_REQUIRED")
                .message(message)
                .pendingActionId(pendingActionId)
                .intent(intent)
                .build();
    }

    public static IntentResponse forbidden(String reason) {
        return IntentResponse.builder().type("FORBIDDEN").message(reason).build();
    }

    public static IntentResponse error(String message) {
        return IntentResponse.builder().type("ERROR").message(message).build();
    }

    public static IntentResponse generalChat(String message) {
        return IntentResponse.builder().type("GENERAL_CHAT").message(message).build();
    }
}
