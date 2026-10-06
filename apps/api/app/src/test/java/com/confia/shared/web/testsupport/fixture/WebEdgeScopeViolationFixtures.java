package com.confia.shared.web.testsupport.fixture;

import com.confia.shared.security.SessionValidity;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Permanent, deliberately violating fixtures for the absence checks of {@code
 * WebEdgeScopeExclusionInventoryTest} (web-edge-foundations design.md, decision 20): each class
 * breaks exactly one absence, so the check's own test can prove it detects a real violation
 * instead of merely finding none. Never production code, never a bean, and imported one class at
 * a time, never by scanning the package.
 *
 * <p>The package sits under {@code com.confia.shared.web}, and not under {@code
 * com.confia.architecture.fixture}, because the institution-name check only looks at {@code
 * ..web..} packages, and because the rules that scan the architecture fixture package must not see
 * these classes.
 */
public final class WebEdgeScopeViolationFixtures {

    private WebEdgeScopeViolationFixtures() {
    }

    /** An implementation of the session validity port, which has none in production. */
    public static final class SessionValidityAdapter implements SessionValidity {

        @Override
        public boolean isActive(UUID sessionId) {
            return false;
        }
    }

    /** A provider that authenticates a credential, which no production class does yet. */
    public static final class CredentialAuthenticationProvider implements AuthenticationProvider {

        @Override
        public Authentication authenticate(Authentication authentication) {
            return null;
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return false;
        }
    }

    /** Something that creates a session cookie, which no production class does yet. */
    public static final class SessionCookieWriter {

        public Cookie sessionCookie() {
            return new Cookie("SESSION", "value");
        }
    }

    /** A chain that puts a role on a route, which no production chain does. */
    public static final class RoleProtectedChain {

        public void protect(HttpSecurity http) throws Exception {
            http.authorizeHttpRequests(requests -> requests.anyRequest().hasRole("ADMIN"));
        }
    }

    /** An operation guarded by a method-level permission expression. */
    public static final class PreAuthorizedOperation {

        @PreAuthorize("hasRole('ADMIN')")
        public void run() {
            // Deliberately empty: only the annotation matters to the check.
        }
    }

    /** An operation guarded by a method-level role list. */
    public static final class SecuredOperation {

        @Secured("ROLE_ADMIN")
        public void run() {
            // Deliberately empty: only the annotation matters to the check.
        }
    }

    /** A DTO of an institution, which no web package may hold yet. */
    public record InstitutionSummaryDto(String name) {
    }
}
