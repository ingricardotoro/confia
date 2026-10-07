package com.confia.architecture.fixture.hashing.outside;

import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;

/**
 * Deliberate violation fixture: a MAC used outside the two permitted packages (BI38).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} rejects {@code javax.crypto.Mac} outside them.
 */
public final class BadMacOutside {

    public Mac mac() throws NoSuchAlgorithmException {
        return Mac.getInstance("HmacSHA256");
    }
}
