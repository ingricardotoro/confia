package com.confia.architecture.fixture.sessionroutes;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Deliberate violation fixture (specs/identity, scenario I216): a route that lists the sessions of
 * the account, which no production class offers yet. Permanent, never removed: it is what proves that
 * {@code NoFullSessionMfaRoutesTest} rejects something. Test code, outside the production scope.
 */
@RequestMapping("/api/v1/auth/sessions")
public final class BadSessionListingController {

    @GetMapping
    public String list() {
        return "sessions";
    }
}
