package com.confia.architecture.fixture.identity.web;

import com.confia.shared.web.idempotency.IdempotentWrite;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Deliberate violation fixture (web-edge-foundations design.md, decisions 19 and 20, rule W5;
 * specs/build-integrity, requirement "El mecanismo HTTP de idempotencia no se aplica a ningún
 * endpoint de identidad"): a sign-in endpoint that applies the {@code Idempotency-Key} mechanism,
 * which would store the tokens of its response in the clear. Physically kept under {@code
 * architecture.fixture.identity}, which {@code IdempotencyNotInIdentityTest} treats as simulating
 * {@code com.confia.identity}, so a real production class never becomes it. Permanent, never
 * removed: it is what proves that rule rejects something (ADR-0018).
 */
public final class BadIdempotentLoginController {

    @IdempotentWrite
    @PostMapping("/login")
    public String login() {
        return "token";
    }
}
