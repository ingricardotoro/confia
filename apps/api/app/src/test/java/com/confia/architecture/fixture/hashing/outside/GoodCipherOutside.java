package com.confia.architecture.fixture.hashing.outside;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;

/**
 * Deliberate non-violation fixture: encryption is out of the rule's scope (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} does <strong>not</strong> reject {@code javax.crypto.Cipher}.
 */
public final class GoodCipherOutside {

    public Cipher cipher() throws GeneralSecurityException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }
}
