package com.confia.architecture.fixture.ratelimit.identity.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberate violation fixture (session-endpoints-cookie-and-csrf design.md decisions 11 and 20;
 * specs/build-integrity, scenario [BI45]; condition H1): a handler of a package named {@code
 * identity.web} that changes state on a session route and declares no rate-limit policy, which is
 * how the login would have been exposed without the limiter. Physically kept under {@code
 * architecture.fixture.ratelimit.identity.web}, so a real production class never becomes it and the
 * rule still evaluates a package it recognizes as the web layer of identity. Permanent, never
 * removed: it is what proves the rule rejects something (ADR-0018).
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public final class BadLoginHandlerWithoutPolicy {

    @PostMapping
    public String login() {
        return "logged in";
    }
}
