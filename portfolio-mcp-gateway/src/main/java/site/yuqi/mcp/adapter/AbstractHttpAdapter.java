package site.yuqi.mcp.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import site.yuqi.mcp.model.ToolDefinition;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Common HTTP forwarding logic shared by all concrete adapters.
 *
 * <ol>
 *   <li>Substitute {@code {placeholder}} segments in the endpoint path from
 *       the args map; consumed keys are removed from the request body /
 *       query string.</li>
 *   <li>Add any bearer token / extra headers via {@link #decorate(WebClient.RequestHeadersSpec, Map)}.</li>
 *   <li>For GET / DELETE, attach remaining args as query parameters.
 *       Otherwise send them as a JSON body.</li>
 *   <li>Surface non-2xx responses as {@link AdapterException} with the
 *       upstream body included.</li>
 * </ol>
 */
@Slf4j
public abstract class AbstractHttpAdapter implements DomainServiceAdapter {

    private static final Pattern PATH_VAR = Pattern.compile("\\{([^/}]+)}");

    protected final WebClient.Builder webClientBuilder;

    protected AbstractHttpAdapter(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /** Subclasses provide the configured base URL for their domain. */
    protected abstract String baseUrl();

    /** Subclasses may set timeout per domain. */
    protected Duration timeout() { return Duration.ofSeconds(15); }

    /** Subclasses inject auth headers (bearer tokens, service tokens). */
    protected void decorate(WebClient.RequestHeadersSpec<?> spec, Map<String, Object> args) {
        // default: no-op
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args)
            throws AdapterException {
        if (tool.getEndpoint() == null) {
            throw new AdapterException("Tool " + tool.getName() + " has no endpoint definition.");
        }
        Map<String, Object> mutable = new HashMap<>(args);
        // Strip gateway-internal control flags so they aren't forwarded.
        mutable.keySet().removeIf(k -> k != null && k.startsWith("_"));

        String path = substitutePathVars(tool.getEndpoint().getPath(), mutable);
        HttpMethod method = HttpMethod.valueOf(tool.getEndpoint().getMethod().toUpperCase());

        WebClient client = webClientBuilder.baseUrl(baseUrl()).build();
        WebClient.RequestBodySpec request;

        if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
            String uri = UriComponentsBuilder.fromPath(path)
                    .queryParams(toQueryParams(mutable))
                    .toUriString();
            request = (WebClient.RequestBodySpec) client.method(method).uri(uri);
        } else {
            request = client.method(method).uri(path);
            if (!mutable.isEmpty()) {
                request = (WebClient.RequestBodySpec) request.bodyValue(mutable);
            }
        }
        decorate(request, mutable);

        try {
            Object result = request
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, resp ->
                            resp.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .flatMap(body -> Mono.error(new WebClientResponseException(
                                            resp.statusCode().value(),
                                            "Downstream " + resp.statusCode(),
                                            resp.headers().asHttpHeaders(),
                                            body.getBytes(),
                                            null))))
                    .bodyToMono(Object.class)
                    .timeout(timeout())
                    .block();

            if (result == null) return Map.of("ok", true);
            if (result instanceof Map<?, ?> m) return (Map<String, Object>) m;
            return Map.of("data", result);
        } catch (WebClientResponseException e) {
            log.warn("Adapter {} → {} {} failed: {} body={}",
                    target(), method, path, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new AdapterException(
                    "Downstream " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(),
                    e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Adapter {} → {} {} failed", target(), method, path, e);
            throw new AdapterException("Downstream call failed: " + e.getMessage(), e);
        }
    }

    private static String substitutePathVars(String path, Map<String, Object> args) {
        Matcher m = PATH_VAR.matcher(path);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object v = args.remove(key);
            if (v == null) {
                throw new AdapterException("Missing path variable {" + key + "} for " + path);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(v.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static org.springframework.util.MultiValueMap<String, String> toQueryParams(Map<String, Object> args) {
        org.springframework.util.LinkedMultiValueMap<String, String> q = new org.springframework.util.LinkedMultiValueMap<>();
        args.forEach((k, v) -> {
            if (v == null) return;
            if (v instanceof java.util.Collection<?> c) {
                c.forEach(item -> q.add(k, String.valueOf(item)));
            } else {
                q.add(k, String.valueOf(v));
            }
        });
        args.clear();
        return q;
    }
}
