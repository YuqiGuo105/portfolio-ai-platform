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
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.bulkhead.*;
import io.github.resilience4j.ratelimiter.*;

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
    private final CircuitBreaker circuit = CircuitBreaker.of("downstream", CircuitBreakerConfig.custom()
            .slidingWindowSize(20).minimumNumberOfCalls(5).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(20)).permittedNumberOfCallsInHalfOpenState(2)
            .recordException(e -> !(e instanceof AdapterException a) || a.getStatusCode()==null || a.getStatusCode()>=500)
            .build());
    private final Bulkhead bulkhead = Bulkhead.of("downstream", BulkheadConfig.custom()
            .maxConcurrentCalls(8).maxWaitDuration(Duration.ZERO).build());
    private final RateLimiter rateLimiter = RateLimiter.of("downstream", RateLimiterConfig.custom()
            .limitForPeriod(40).limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO).build());

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

    protected void decorate(WebClient.RequestHeadersSpec<?> spec, Map<String, Object> args,
                            Map<String, Object> controlArgs) {
        decorate(spec, args);
    }

    /** Subclasses may translate catalog argument names into downstream API names. */
    protected void prepareArgs(ToolDefinition tool, Map<String, Object> args) {
        // default: no-op
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> invoke(ToolDefinition tool, Map<String, Object> args)
            throws AdapterException {
        try {
            return RateLimiter.decorateSupplier(rateLimiter,
                    Bulkhead.decorateSupplier(bulkhead,
                            CircuitBreaker.decorateSupplier(circuit, () -> invokeOnce(tool,args)))).get();
        } catch (CallNotPermittedException | BulkheadFullException | RequestNotPermitted e) {
            throw AdapterException.unavailableBeforeDispatch("Downstream temporarily unavailable; retry the same operation key after 5 seconds.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> invokeOnce(ToolDefinition tool,Map<String,Object> args) {
        if (tool.getEndpoint() == null) {
            throw new AdapterException("Tool " + tool.getName() + " has no endpoint definition.");
        }
        Map<String, Object> mutable = new HashMap<>(args);
        Map<String, Object> controlArgs = new HashMap<>();
        mutable.forEach((key, value) -> {
            if (key != null && key.startsWith("_")) controlArgs.put(key, value);
        });
        // Strip gateway-internal control flags so they aren't forwarded.
        mutable.keySet().removeIf(k -> k != null && k.startsWith("_"));
        prepareArgs(tool, mutable);

        String path = substitutePathVars(tool.getEndpoint().getPath(), mutable);
        HttpMethod method = HttpMethod.valueOf(tool.getEndpoint().getMethod().toUpperCase());

        WebClient client = webClientBuilder.clone().baseUrl(baseUrl()).build();
        WebClient.RequestBodySpec request;

        if (method == HttpMethod.GET || method == HttpMethod.DELETE) {
            // Expand query values exactly once; passing an encoded String to WebClient encodes it again.
            UriComponentsBuilder uri = UriComponentsBuilder.fromUriString(baseUrl()).path(path);
            Map<String, Object> variables = new HashMap<>();
            toQueryParams(mutable).forEach((key, values) -> values.forEach(value -> {
                String variable = "query" + variables.size();
                variables.put(variable, value);
                uri.queryParam(key, "{" + variable + "}");
            }));
            request = (WebClient.RequestBodySpec) client.method(method)
                    .uri(uri.encode().buildAndExpand(variables).toUri());
        } else {
            request = client.method(method).uri(path);
            if (!mutable.isEmpty()) {
                request = (WebClient.RequestBodySpec) request.bodyValue(mutable);
            }
        }
        decorate(request, mutable, controlArgs);
        if(controlArgs.get("_idempotencyKey")!=null) request.header("Idempotency-Key",String.valueOf(controlArgs.get("_idempotencyKey")));
        if(controlArgs.get("_operationId")!=null) request.header("X-Operation-Id",String.valueOf(controlArgs.get("_operationId")));

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
                    .retryWhen(reactor.util.retry.Retry.backoff(
                            tool.getMode()!=null && tool.getMode().name().equals("READ") ? 1 : 0, Duration.ofMillis(250))
                            .jitter(0.5)
                            .filter(e -> e instanceof WebClientResponseException w &&
                                    java.util.Set.of(502,503,504).contains(w.getStatusCode().value()))
                            .onRetryExhaustedThrow((spec,signal) -> signal.failure()))
                    .timeout(timeout())
                    .block();

            if (result == null) return Map.of("ok", true);
            if (result instanceof Map<?, ?> m) return (Map<String, Object>) m;
            return Map.of("data", result);
        } catch (WebClientResponseException e) {
            log.warn("Adapter {} failed with HTTP {}", target(), e.getStatusCode().value());
            throw new AdapterException(
                    "Downstream returned HTTP " + e.getStatusCode().value(),
                    e.getStatusCode().value());
        } catch (Exception e) {
            log.warn("Adapter {} transport failure type={}", target(), e.getClass().getSimpleName());
            throw new AdapterException("Downstream transport failed; execution outcome may be unknown", e);
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
