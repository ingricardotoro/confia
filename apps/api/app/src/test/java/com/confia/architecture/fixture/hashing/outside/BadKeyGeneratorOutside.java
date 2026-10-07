package com.confia.architecture.fixture.hashing.outside;

import java.security.NoSuchAlgorithmException;
import javax.crypto.KeyGenerator;

/**
 * Deliberate violation fixture: a secret key generator used outside the two permitted packages (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} rejects {@code javax.crypto.KeyGenerator} outside them.
 */
public final class BadKeyGeneratorOutside {

    public KeyGenerator generator() throws NoSuchAlgorithmException {
        return KeyGenerator.getInstance("AES");
    }
}
