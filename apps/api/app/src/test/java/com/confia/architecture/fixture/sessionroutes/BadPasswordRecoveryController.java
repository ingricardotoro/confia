package com.confia.architecture.fixture.sessionroutes;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Deliberate violation fixture (build-integrity, scenario BI52): a route that starts the recovery of a
 * password over HTTP, which no production class offers yet (owner: {@code
 * password-recovery-endpoints}). Permanent, never removed. Test code, outside the production scope.
 */
@RequestMapping("/api/v1/auth")
public final class BadPasswordRecoveryController {

    @PostMapping("/password-recovery")
    public String start() {
        return "started";
    }
}
