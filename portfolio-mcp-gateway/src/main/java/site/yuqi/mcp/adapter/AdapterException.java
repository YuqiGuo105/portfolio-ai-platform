package site.yuqi.mcp.adapter;

/** Wraps any transport / HTTP error from a downstream domain service. */
public class AdapterException extends RuntimeException {

    private final Integer statusCode;

    public AdapterException(String message) {
        super(message);
        this.statusCode = null;
    }

    public AdapterException(String message, Integer statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public AdapterException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = null;
    }

    public Integer getStatusCode() {
        return statusCode;
    }
}
