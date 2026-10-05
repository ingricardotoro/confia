package com.confia.shared.web.harness;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controllers behind the real security chain. They answer every HTTP method, so a
 * response that is not the chain's own denial is visible as a {@code 2xx}: the chain, not the
 * mapping, is what the tests prove.
 */
@RestController
class HarnessController {

    private final Calls calls;

    HarnessController(Calls calls) {
        this.calls = calls;
    }

    /** Registered and public: the harness adds it to the allow-list for {@code GET} only. */
    @RequestMapping("/test/open")
    String open() {
        calls.openInvoked();
        return "open";
    }

    /** Registered and not on the allow-list: every request must be denied before this runs. */
    @RequestMapping("/x")
    String protectedRoute() {
        calls.protectedInvoked();
        return "protected";
    }
}
