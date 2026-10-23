package com.confia.shared.web.authentication;

import com.confia.shared.security.PendingSecondFactor;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Spring Security's view of the holder of a restricted token, so the chain's authorization rule
 * {@code authenticated()} can see one on the routes of the restricted class
 * (session-endpoints-cookie-and-csrf design.md, decision 6). It is not an {@link ActorAuthentication}:
 * it carries a {@link PendingSecondFactor}, has no authority, and no consumer of the session actor
 * can mistake it for a session. The principal is transient for the same reason the actor's is: the
 * chain is stateless and nothing may persist it.
 */
public final class SecondFactorAuthentication implements Authentication {

    private static final long serialVersionUID = 1L;

    private final transient PendingSecondFactor pending;

    public SecondFactorAuthentication(PendingSecondFactor pending) {
        this.pending = Objects.requireNonNull(pending, "pending");
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getDetails() {
        return null;
    }

    @Override
    public PendingSecondFactor getPrincipal() {
        return pending;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        throw new UnsupportedOperationException(
                "a second-factor authentication is always authenticated");
    }

    @Override
    public String getName() {
        return pending.accountId().toString();
    }

    @Override
    public String toString() {
        return "SecondFactorAuthentication[" + pending + "]";
    }
}
