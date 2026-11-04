package com.confia.shared.web.authorization;

import com.confia.shared.web.idempotency.IdempotentWrite;
import com.confia.shared.web.ratelimit.RateLimited;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only routes of {@link AuthorizationEdgeIT}, behind the real administrative chain. The write
 * stands for a money-moving endpoint: it demands an {@code Idempotency-Key} and spends a quota of the
 * limiter, so a request that is denied is proved to have reached neither. Both routes count their
 * invocations.
 */
@RestController
class AuthorizationTestController {

    static final String PAYMENTS = "/test/authorization/payments";
    static final String ACCOUNTS = "/test/authorization/accounts/{id}";

    private final AtomicInteger invocations = new AtomicInteger();

    @IdempotentWrite
    @RateLimited(policy = "admin-session")
    @PostMapping(PAYMENTS)
    String pay() {
        invocations.incrementAndGet();
        return "paid";
    }

    @GetMapping(ACCOUNTS)
    String account(@PathVariable(name = "id") String id) {
        invocations.incrementAndGet();
        return "account";
    }

    int invocations() {
        return invocations.get();
    }
}
