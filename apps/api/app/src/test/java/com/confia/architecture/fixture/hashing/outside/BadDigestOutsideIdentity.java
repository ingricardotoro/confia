package com.confia.architecture.fixture.hashing.outside;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deliberate violation fixture standing in for a {@code com.confia.shared.web} class that computes a hash (BI11).
 * Permanent, never removed: it is what proves {@link
 * com.confia.architecture.SigningAndHashingConfinementTest} rejects a hash computed outside {@code identity} and {@code shared.security}.
 */
public final class BadDigestOutsideIdentity {

    public byte[] hashes(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }
}
