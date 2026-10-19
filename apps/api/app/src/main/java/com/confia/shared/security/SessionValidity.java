package com.confia.shared.security;

/**
 * Whether a session is still valid, declared here so that {@code shared} never depends on {@code
 * identity} (web-edge-foundations design.md, decision 14). {@code identity} implements it with
 * {@code SessionLiveness}; the authentication filter is the only class that asks it, and only for
 * the routes whose entry in the authenticated list declares the check
 * (session-tokens-and-web-layer design.md, decisions 5 and 7).
 *
 * <p>It takes the whole actor and not just the session identifier: the adapter has to open a
 * transaction whose row-level-security parameters are the actor's institution and account, and with
 * only a session id it could set neither, so the policy would deny every row.
 */
public interface SessionValidity {

    /**
     * Whether the session {@code actor} was authenticated with is still valid at this instant: its
     * family exists for the actor's institution and account, is not revoked and has neither reached
     * its absolute end nor been idle for the whole inactivity period.
     *
     * <p>A failure of the store is thrown, never answered: an error must not read as "active".
     */
    boolean isActive(AuthenticatedActor actor);
}
