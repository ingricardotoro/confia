package com.confia.shared.web.harness;

import com.confia.shared.web.ratelimit.RateLimited;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registered only when {@code harness.unknown-policy=true}: a route that names a policy no limiter
 * holds, to prove the process refuses to start and says which policy it was.
 */
@RestController
@ConditionalOnProperty(name = "harness.unknown-policy", havingValue = "true")
class UnknownPolicyController {

    @RateLimited(policy = "no-such-policy")
    @GetMapping("/test/unknown-policy")
    String unknown() {
        return "never";
    }
}
