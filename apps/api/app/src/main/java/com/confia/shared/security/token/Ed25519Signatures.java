package com.confia.shared.security.token;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

/**
 * Ed25519 over the JDK's own {@link Signature} (RFC 8032; session-tokens-and-web-layer design.md,
 * decision 2 and probes S-1 and S-2 of section 2.1). A {@code Signature} is not safe between
 * threads, so every call gets its own instance.
 *
 * <p>The JDK does not answer {@code false} for a malformed signature: it throws {@link
 * SignatureException} for one whose scalar is not below the group order (the malleable {@code S + L})
 * and for one that is not 64 bytes long. {@link #verify} turns both into {@code false}, so a hostile
 * signature is always an invalid token and never an internal error.
 */
public final class Ed25519Signatures {

    /** The length of an Ed25519 signature: the point R and the scalar S, 32 bytes each. */
    public static final int SIGNATURE_LENGTH = 64;

    private static final String ALGORITHM = "Ed25519";

    private Ed25519Signatures() {
    }

    public static byte[] sign(PrivateKey key, byte[] message) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(key);
            signature.update(message);
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (SignatureException e) {
            return false;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 verification is not possible with this key", e);
        }
    }
}
