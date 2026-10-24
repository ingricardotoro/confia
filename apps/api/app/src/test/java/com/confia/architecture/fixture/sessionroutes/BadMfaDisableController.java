package com.confia.architecture.fixture.sessionroutes;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Deliberate violation fixture (specs/identity, scenario I216): routes that enroll and remove a second
 * factor outside the two restricted routes, which no production class offers yet. Permanent, never
 * removed. Test code, outside the production scope.
 */
@RequestMapping("/api/v1/auth/mfa")
public final class BadMfaDisableController {

    @DeleteMapping
    public String disable() {
        return "disabled";
    }

    @PostMapping("/totp-secrets")
    public String enrollAgain() {
        return "enrolled";
    }
}
