package com.confia.shared.web.idempotency;

import com.confia.shared.security.IdempotentResponse;
import com.confia.shared.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The only endpoint that applies the HTTP idempotency mechanism in this change (web-edge-foundations
 * design.md, decision 19). It lives in the test tree and is registered only in the database harness
 * of {@code IdempotencyEdgeIT}: no process loads it, and {@code IdempotencyDemoAbsentFromProcessesTest}
 * proves it. The institution comes from a header because no authentication exists yet (ADR-0009); a
 * real endpoint takes it from the principal.
 *
 * <p>The use case answers with a receipt number it generates, so a repeated request that returned
 * the same number could only have been answered from the stored response, never recomputed.
 */
@RestController
public class IdempotencyDemoController {

    static final String PAYMENTS = "/test/idempotency/payments";
    static final String ACCOUNT_PAYMENTS = "/test/idempotency/accounts/{accountId}/payments";
    static final String READ_ONLY = "/test/idempotency/receipts";
    static final String INSTITUTION_HEADER = "X-Test-Institution";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IdempotentRequestHandler idempotency;
    private final DemoProbe probe;

    IdempotencyDemoController(IdempotentRequestHandler idempotency, DemoProbe probe) {
        this.idempotency = idempotency;
        this.probe = probe;
    }

    @IdempotentWrite
    @PostMapping(PAYMENTS)
    ResponseEntity<JsonNode> pay(HttpServletRequest request,
            @RequestHeader(name = INSTITUTION_HEADER) String institution,
            @RequestBody JsonNode body) {
        return idempotency.handle(request, contextOf(institution), body, () -> receipt(body));
    }

    /** The same operation on another route, whose path variable is part of what the key covers. */
    @IdempotentWrite
    @PostMapping(ACCOUNT_PAYMENTS)
    ResponseEntity<JsonNode> payOnAccount(HttpServletRequest request,
            @RequestHeader(name = INSTITUTION_HEADER) String institution,
            @PathVariable(name = "accountId") String accountId, @RequestBody JsonNode body) {
        return idempotency.handle(request, contextOf(institution), body, () -> receipt(body));
    }

    /** Read-only: it does not declare the mechanism and needs no key. */
    @GetMapping(READ_ONLY)
    String read() {
        return "ok";
    }

    private IdempotentResponse receipt(JsonNode body) {
        probe.useCaseRan();
        return new IdempotentResponse(201, JSON.createObjectNode()
                .put("receipt", UUID.randomUUID().toString()).set("echo", body));
    }

    private static SecurityContext contextOf(String institution) {
        return new SecurityContext("", "system", institution, UUID.randomUUID().toString());
    }
}
