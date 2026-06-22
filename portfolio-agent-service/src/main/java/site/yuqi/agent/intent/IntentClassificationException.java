package site.yuqi.agent.intent;

/** Thrown when an LLM response cannot be safely parsed into an IntentResult. */
public class IntentClassificationException extends RuntimeException {
    public IntentClassificationException(String message) {
        super(message);
    }

    public IntentClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
