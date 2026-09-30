package com.confia.architecture.fixture.identity;

import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.AuthenticationResult.Authenticated;

/**
 * Deliberate violation fixture (column-encryption-and-mfa-totp design.md, decision 6; §9, sonda
 * S4). Physically kept under {@code architecture.fixture.identity} rather than {@code
 * com.confia.identity} itself, so a real production class never accidentally becomes this fixture
 * — the same permanent-rejection pattern {@link BadBlockingWaitInIdentity} already establishes.
 * {@link com.confia.architecture.NoInstanceofOnAuthenticationResultTest} treats this package as
 * "simulating" {@code com.confia.identity} for the sole purpose of proving its rule actually
 * rejects an {@code instanceof} check against {@link AuthenticationResult}: exactly the regression
 * this rule exists to catch if {@code AuthenticateWithPassword} — or any future consumer — ever
 * reverts its exhaustive {@code switch} back to {@code instanceof}, silently losing the
 * compile-time guarantee decision 6's fixture demonstrates.
 */
public final class BadInstanceofOnAuthenticationResult {

    public boolean checksInstanceofDirectly(AuthenticationResult result) {
        return result instanceof Authenticated;
    }
}
