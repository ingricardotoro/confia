package com.confia.identity.infrastructure;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Computes {@link IdentifierFingerprint}s with a subkey derived from the Argon2id pepper, never
 * with the pepper directly (design.md, decision 6, "Subllave para la huella del identificador, con
 * separación de dominio"): {@code HMAC-SHA-256(pepper, "confia.identity.login-identifier.v1")} is
 * the subkey, and the fingerprint itself is {@code HMAC-SHA-256(subkey, identifier)}, hex-encoded.
 * Reusing the pepper directly for two unrelated primitives — password hashing and identifier
 * fingerprinting — is a cheap mistake this domain separation exists to rule out.
 *
 * <p>Matches the eventual shape of the {@code LoginIdentifierFingerprinter} port
 * {@code identity.application} declares in PR C3a without implementing it yet — that port does not
 * exist until that later cut (design.md §11, paso 15).
 */
public final class HmacLoginIdentifierFingerprinter {

    static final String DOMAIN_SEPARATION_LABEL = "confia.identity.login-identifier.v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] subkey;

    public HmacLoginIdentifierFingerprinter(Argon2Pepper pepper) {
        Objects.requireNonNull(pepper, "pepper");
        this.subkey = hmac(pepper.value(),
                DOMAIN_SEPARATION_LABEL.getBytes(StandardCharsets.UTF_8));
    }

    public IdentifierFingerprint fingerprintOf(LoginIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        byte[] digest = hmac(subkey, identifier.value().getBytes(StandardCharsets.UTF_8));
        return new IdentifierFingerprint(HexFormat.of().formatHex(digest));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(HMAC_ALGORITHM + " must always be available on the JVM", e);
        }
    }
}
