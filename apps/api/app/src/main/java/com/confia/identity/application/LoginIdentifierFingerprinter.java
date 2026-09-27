package com.confia.identity.application;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;

/**
 * Computes the keyed {@link IdentifierFingerprint} that indexes {@code identity_login_backoff}
 * (design.md, §6.1, decision 3). {@link
 * com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter} is its one adapter.
 */
public interface LoginIdentifierFingerprinter {

    IdentifierFingerprint fingerprintOf(LoginIdentifier identifier);
}
