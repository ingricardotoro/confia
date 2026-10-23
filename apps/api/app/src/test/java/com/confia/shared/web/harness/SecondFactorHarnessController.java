package com.confia.shared.web.harness;

import com.confia.organization.application.CurrentInstitutionProvider;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.PendingSecondFactor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controllers on the restricted second-factor class of the harness chain (decision 6): they
 * report what a handler of those routes can see, so a test proves the restricted principal is bound
 * and the session actor is not, and that nothing that reads the institution from the actor works
 * behind a restricted token.
 */
@RestController
class SecondFactorHarnessController {

    private final Calls calls;
    private final CurrentInstitutionProvider institutions;

    SecondFactorHarnessController(Calls calls, CurrentInstitutionProvider institutions) {
        this.calls = calls;
        this.institutions = institutions;
    }

    /** What a handler of a restricted route sees of the request it serves. */
    record PendingView(boolean pendingPresent, String purpose, String accountId,
            String institutionId, boolean actorPresent, String springPrincipal,
            boolean springAuthenticated, String institutionProvider) {
    }

    /** On the restricted class for {@code POST}, purposes {@code mfa-verify} and {@code mfa-enroll}. */
    @PostMapping("/test/second-factor/verify")
    PendingView verify() {
        return view();
    }

    /** On the restricted class for {@code POST}, purpose {@code mfa-enroll} only. */
    @PostMapping("/test/second-factor/enroll")
    PendingView enroll() {
        return view();
    }

    /** On the authenticated list for {@code DELETE}: stands for the close-all route of a later task. */
    @DeleteMapping("/test/sessions")
    String closeAll() {
        calls.authenticatedInvoked();
        return "closed";
    }

    private PendingView view() {
        calls.secondFactorInvoked();
        PendingSecondFactor pending = PendingSecondFactor.current().orElse(null);
        String provider;
        try {
            institutions.currentInstitutionId();
            provider = "resolved";
        } catch (IllegalStateException refused) {
            provider = "refused";
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return new PendingView(pending != null,
                pending == null ? null : pending.purpose().name(),
                pending == null ? null : pending.accountId().toString(),
                pending == null ? null : pending.institutionId().toString(),
                AuthenticatedActor.current().isPresent(),
                authentication == null ? null : authentication.getClass().getSimpleName(),
                authentication != null && authentication.isAuthenticated(), provider);
    }
}
