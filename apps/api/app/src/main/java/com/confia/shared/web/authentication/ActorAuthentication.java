package com.confia.shared.web.authentication;

import com.confia.shared.security.AuthenticatedActor;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Spring Security's view of an authenticated actor, so the chain's authorization rule {@code
 * authenticated()} can see one (session-tokens-and-web-layer design.md, decision 5). It carries the
 * actor and nothing else: no authority, because no route grants a permission yet, and no credential,
 * because the token is not kept past the filter. {@code toString} is the actor's, which leaves out
 * the token identifier.
 */
public final class ActorAuthentication implements Authentication {

    private static final long serialVersionUID = 1L;

    private final AuthenticatedActor actor;

    public ActorAuthentication(AuthenticatedActor actor) {
        this.actor = Objects.requireNonNull(actor, "actor");
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
    public AuthenticatedActor getPrincipal() {
        return actor;
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void setAuthenticated(boolean authenticated) {
        throw new UnsupportedOperationException("an actor authentication is always authenticated");
    }

    @Override
    public String getName() {
        return actor.accountId().toString();
    }

    @Override
    public String toString() {
        return "ActorAuthentication[" + actor + "]";
    }
}
