package com.confia.architecture.fixture.hashing.outside;

import java.security.NoSuchAlgorithmException;
import java.security.Signature;

/**
 * Deliberate violation fixture: a signature utility used outside the two permitted packages (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} rejects {@code java.security.Signature} outside them.
 */
public final class BadSignatureOutside {

    public Signature signer() throws NoSuchAlgorithmException {
        return Signature.getInstance("Ed25519");
    }
}
