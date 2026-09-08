package site.yuqi.mcp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import site.yuqi.mcp.idempotency.IdempotencyKeyService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Tracks owner writes executed by the edge with the owner's verified JWT. */
@RestController
@RequestMapping("/api/owner-operations/{tool}")
public class OwnerOperationController {
    private final IdempotencyKeyService ledger;
    private final String token;
    public OwnerOperationController(IdempotencyKeyService ledger,@Value("${mcp.internal-token:}") String token) {
        this.ledger=ledger; this.token=token;
    }
    @PostMapping("/claim")
    public Map<String,Object> claim(@PathVariable String tool,@RequestHeader("Authorization") String auth,
            @RequestHeader("X-Actor") String actor,@RequestHeader("X-Role") String role,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> args) {
        authorize(tool,auth,role); return ledger.claim(actor,tool,key,args);
    }
    @PostMapping("/complete")
    public Map<String,Object> complete(@PathVariable String tool,@RequestHeader("Authorization") String auth,
            @RequestHeader("X-Actor") String actor,@RequestHeader("X-Role") String role,@RequestBody Completion body) {
        authorize(tool,auth,role);
        return ledger.complete(actor,body.claim(),body.state(),body.httpStatus(),body.response());
    }
    private void authorize(String tool,String actual,String role) {
        if(token.isBlank() || !"ADMIN".equals(role) || !Set.of("admin.upsert_admin_user","admin.update_admin_user_status").contains(tool)
                || !MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    public record Completion(Map<String,Object> claim,String state,int httpStatus,Map<String,Object> response) {}
}
