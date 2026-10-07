package com.confia.architecture.fixture.hashing.outside;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;

/**
 * Deliberate violation fixture: a key pair generator used outside the two permitted packages (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} rejects {@code java.security.KeyPairGenerator} outside them.
 */
public final class BadKeyPairGeneratorOutside {

    public KeyPairGenerator generator() throws NoSuchAlgorithmException {
        return KeyPairGenerator.getInstance("Ed25519");
    }
}
