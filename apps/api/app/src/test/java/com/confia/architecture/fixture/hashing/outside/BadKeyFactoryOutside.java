package com.confia.architecture.fixture.hashing.outside;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;

/**
 * Deliberate violation fixture: a key factory used outside the two permitted packages (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} rejects {@code java.security.KeyFactory} outside them.
 */
public final class BadKeyFactoryOutside {

    public KeyFactory factory() throws NoSuchAlgorithmException {
        return KeyFactory.getInstance("Ed25519");
    }
}
