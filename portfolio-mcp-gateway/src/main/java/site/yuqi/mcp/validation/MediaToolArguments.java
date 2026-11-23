package site.yuqi.mcp.validation;

import site.yuqi.mcp.model.ToolDefinition;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Fail closed before an identifier reaches HTTP path substitution. */
final class MediaToolArguments {
    private MediaToolArguments() { }

    static void validate(ToolDefinition tool, Map<String, Object> args) {
        var allowed = tool.getParameters().stream().map(ToolDefinition.ParameterSpec::getName).collect(Collectors.toSet());
        for (String key : args.keySet()) {
            if (!key.startsWith("_") && !allowed.contains(key))
                throw new IllegalArgumentException("Unknown media argument: " + key);
        }
        for (String key : Set.of("id", "sourceId")) if (args.containsKey(key) && !(args.get(key) instanceof String id
                && id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
            throw new IllegalArgumentException(key + " must be a UUID");
        if (args.containsKey("expectedContentHash") && !(args.get("expectedContentHash") instanceof String hash && hash.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("expectedContentHash must be a SHA-256 digest");
        choice(args, "sourceType", Set.of("BLOG", "PROJECT"));
        choice(args, "position", Set.of("START", "END"));
        choice(args, "kind", Set.of("VIDEO", "IMAGE"));
        choice(args, "status", Set.of("UPLOADING", "PROCESSING", "READY", "FAILED"));
        number(args, "limit", 1, 100);
        number(args, "expectedVersion", 0, Integer.MAX_VALUE);
        number(args, "sizeBytes", 1, Long.MAX_VALUE);
    }

    private static void choice(Map<String, Object> args, String name, Set<String> choices) {
        if (args.containsKey(name) && (!(args.get(name) instanceof String value) || !choices.contains(value)))
            throw new IllegalArgumentException("Invalid media " + name);
    }

    private static void number(Map<String, Object> args, String name, long min, long max) {
        if (!args.containsKey(name)) return;
        Object value = args.get(name);
        if (!(value instanceof Integer || value instanceof Long))
            throw new IllegalArgumentException(name + " must be an integer");
        long n = ((Number) value).longValue();
        if (n < min || n > max) throw new IllegalArgumentException(name + " is outside the allowed range");
    }
}
