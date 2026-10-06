package com.confia.shared.web.harness;

import com.confia.shared.web.ratelimit.RateLimited;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller whose route carries the rate limit of the administrative login, with a
 * stand-in use case behind it. Both count their invocations, so a test can prove that a request
 * the limiter rejects reached neither the controller nor the use case (web-edge spec: "una
 * petición rechazada no tiene efecto"). Public for {@code GET} in the harness allow-list.
 */
@RestController
class LimitedController {

    private final Calls calls;

    LimitedController(Calls calls) {
        this.calls = calls;
    }

    @RateLimited(policy = "admin-login")
    @GetMapping("/test/limited")
    String limited() {
        calls.limitedInvoked();
        return useCase();
    }

    /** The use case of the stand-in: it only records that it ran. */
    private String useCase() {
        calls.useCaseInvoked();
        return "done";
    }

    /** A route with no limit, to show the limiter touches only what is annotated. */
    @GetMapping("/test/unlimited")
    String unlimited() {
        return "free";
    }
}
