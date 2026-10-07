package com.confia.architecture.fixture.hashing.outside;

import java.security.SecureRandom;

/**
 * Deliberate non-violation fixture: the random generator is out of the rule's scope (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} does <strong>not</strong> reject {@code SecureRandom}.
 */
public final class GoodSecureRandomOutside {

    public SecureRandom random() {
        return new SecureRandom();
    }
}
