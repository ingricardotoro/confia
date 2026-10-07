package com.confia.architecture.fixture.hashing.outside;

import org.bouncycastle.crypto.digests.SHA256Digest;

/**
 * Deliberate violation fixture: a Bouncy Castle class used outside the two permitted packages (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} rejects any {@code org.bouncycastle} class outside them.
 */
public final class BadBouncyCastleOutside {

    public SHA256Digest digest() {
        return new SHA256Digest();
    }
}
