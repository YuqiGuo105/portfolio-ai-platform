package site.yuqi.mcp.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;

/** The admin database owns atomic admission and durable results across gateway instances. */
@Service
public class IdempotencyKeyService {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final String secret;
    public IdempotencyKeyService(WebClient.Builder builder, ObjectMapper mapper,
            @Value("${domain.admin.base-url}") String url,
            @Value("${domain.admin.admin-secret:}") String secret) {
        this.client=builder.clone().baseUrl(url).build(); this.mapper=mapper; this.secret=secret;
    }
    public Map<String,Object> claim(String principal,String tool,String key,Map<String,Object> args) {
        if(key==null || key.length()<8 || key.length()>200)
            throw new LedgerException("idempotency_key_required",400);
        return post("/claim",Map.of("principal",principal,"tool",tool,"idempotencyKey",key,"requestHash",hash(args)));
    }
    public Map<String,Object> complete(String principal,Map<String,Object> claim,String state,int httpStatus,Map<String,Object> result) {
        return post("/"+claim.get("operationId")+"/complete",Map.of("principal",principal,
                "leaseToken",claim.get("leaseToken"),"state",state,"httpStatus",httpStatus,"response",result));
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> post(String path,Map<String,Object> body) {
        if(secret.isBlank()) throw new LedgerException("operation_store_unavailable",503);
        try {
            Map<String,Object> result=client.post().uri("/api/admin/mcp-operations"+path)
                    .header("X-Admin-Secret",secret).bodyValue(body).retrieve().bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(8)).block();
            if(result==null) throw new LedgerException("operation_store_unavailable",503);
            return result;
        } catch(WebClientResponseException e) {
            throw new LedgerException(e.getStatusCode().value()==409?"idempotency_conflict":"operation_store_unavailable",
                    e.getStatusCode().value()==409?409:503);
        } catch(LedgerException e) { throw e; }
        catch(Exception e) { throw new LedgerException("operation_store_unavailable",503); }
    }
    public String hash(Map<String,Object> args) {
        var payload=new HashMap<>(args);
        payload.keySet().removeIf(k -> k.startsWith("_"));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(canonical(payload)))); }
        catch(Exception e) { throw new IllegalArgumentException("Cannot hash tool arguments",e); }
    }
    private Object canonical(Object value) {
        if(value instanceof Map<?,?> m) {
            var result=new TreeMap<String,Object>();
            m.forEach((k,v)-> result.put(String.valueOf(k),canonical(v)));
            return result;
        }
        if(value instanceof List<?> l) return l.stream().map(this::canonical).toList();
        return value;
    }
    public static class LedgerException extends RuntimeException {
        public final int status;
        public LedgerException(String code,int status) { super(code); this.status=status; }
    }
}
