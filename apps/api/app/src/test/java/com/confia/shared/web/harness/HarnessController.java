package com.confia.shared.web.harness;

import com.confia.shared.security.RequestOrigin;
import com.confia.shared.web.problem.ProblemResponses;
import com.confia.shared.web.request.RequestContextFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
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

    /**
     * Public for {@code GET}: answers with what the request context holds for this request, so a
     * test can compare it with what the client sent. The two ids are the one in the request
     * attribute and the one in the scoped origin; they must be the same value.
     */
    @GetMapping("/test/origin")
    OriginView origin(HttpServletRequest request) {
        RequestOrigin origin = RequestOrigin.current().orElseThrow(
                () -> new IllegalStateException("no request origin is bound to this request"));
        return new OriginView(origin.requestId().toString(),
                (String) request.getAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE),
                origin.clientAddress() == null ? null : origin.clientAddress().canonical(),
                origin.userAgent());
    }

    /** What {@link #origin} reports; a missing agent is {@code null}. */
    record OriginView(String requestId, String requestIdAttribute, String sourceIp,
            String userAgent) {
    }

    /** Public for {@code GET} and always failing, with a message that must never reach a client. */
    @RequestMapping("/test/boom")
    String boom(HttpServletRequest request) {
        calls.failingInvoked(
                (String) request.getAttribute(ProblemResponses.REQUEST_ID_ATTRIBUTE),
                MDC.get(RequestContextFilter.MDC_KEY));
        throw new IllegalStateException("jdbc:postgresql://host/db password=x");
    }
}
