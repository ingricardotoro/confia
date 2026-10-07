package com.confia.shared.security.token;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.core.env.Environment;

/**
 * Builds the {@link SigningKeyRing} from the process environment (session-tokens-and-web-layer
 * design.md, decision 3). Every check happens here, while the context is being created, so a bad
 * configuration stops the process at startup and never fails the first request.
 *
 * <p>The values are read with {@link Environment#getProperty(String)}, never bound with
 * {@code @ConfigurationProperties} or {@code @Value}: Spring Boot's binding failure analyzer prints
 * the rejected value, and a key must never reach a log (CLAUDE.md, regla 11). Every failure is an
 * {@link IllegalStateException} that names the property and repeats no part of any value, and
 * that chains no cause, because the message of a decoder or of a key factory can carry a fragment
 * of its input.
 *
 * <p>The public key is required next to the private one because the JDK offers no way to derive the
 * public key of an Ed25519 private key. In exchange the loader signs 32 random bytes with the
 * private key and verifies them with the public one, so a pair that does not correspond stops the
 * process. A private key of the previous key is refused: the previous key only verifies.
 */
final class SigningKeyLoader {

    static final String CURRENT_KID = "confia.security.admin-signing.current.kid";
    static final String CURRENT_PRIVATE_KEY = "confia.security.admin-signing.current.private-key";
    static final String CURRENT_PUBLIC_KEY = "confia.security.admin-signing.current.public-key";
    static final String PREVIOUS_KID = "confia.security.admin-signing.previous.kid";
    static final String PREVIOUS_PRIVATE_KEY = "confia.security.admin-signing.previous.private-key";
    static final String PREVIOUS_PUBLIC_KEY = "confia.security.admin-signing.previous.public-key";

    private static final String ED25519 = "Ed25519";
    private static final int PROBE_LENGTH = 32;

    private SigningKeyLoader() {
    }

    static SigningKeyRing load(Environment environment) {
        String currentKid = required(environment, CURRENT_KID);
        requireValidKid(currentKid, CURRENT_KID);
        String privateText = required(environment, CURRENT_PRIVATE_KEY);
        String publicText = required(environment, CURRENT_PUBLIC_KEY);
        PrivateKey privateKey = decodePrivateKey(privateText, CURRENT_PRIVATE_KEY);
        PublicKey publicKey = decodePublicKey(publicText, CURRENT_PUBLIC_KEY);
        requireMatchingPair(privateKey, publicKey);
        SigningKey current = SigningKey.signing(currentKid, publicKey, privateKey);

        SigningKey previous = loadPrevious(environment, current);
        return previous == null ? SigningKeyRing.of(current) : SigningKeyRing.of(current, previous);
    }

    private static SigningKey loadPrevious(Environment environment, SigningKey current) {
        if (environment.containsProperty(PREVIOUS_PRIVATE_KEY)) {
            throw failure(PREVIOUS_PRIVATE_KEY, "must not be set: the previous key only verifies "
                    + "and never holds a private key");
        }
        String kid = optional(environment, PREVIOUS_KID);
        String publicText = optional(environment, PREVIOUS_PUBLIC_KEY);
        if (kid == null && publicText == null) {
            return null;
        }
        if (kid == null) {
            throw failure(PREVIOUS_KID, "is required when " + PREVIOUS_PUBLIC_KEY + " is set");
        }
        if (publicText == null) {
            throw failure(PREVIOUS_PUBLIC_KEY, "is required when " + PREVIOUS_KID + " is set");
        }
        requireValidKid(kid, PREVIOUS_KID);
        if (kid.equals(current.kid())) {
            throw failure(PREVIOUS_KID, "must differ from " + CURRENT_KID);
        }
        PublicKey publicKey = decodePublicKey(publicText, PREVIOUS_PUBLIC_KEY);
        if (Arrays.equals(publicKey.getEncoded(), current.publicKey().getEncoded())) {
            throw failure(PREVIOUS_PUBLIC_KEY, "must differ from " + CURRENT_PUBLIC_KEY);
        }
        return SigningKey.verifyOnly(kid, publicKey);
    }

    private static String required(Environment environment, String property) {
        String value = optional(environment, property);
        if (value == null) {
            throw failure(property, "is required and was not set");
        }
        return value;
    }

    /** The value, or {@code null} when the property is absent or blank. */
    private static String optional(Environment environment, String property) {
        String value = environment.getProperty(property);
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireValidKid(String kid, String property) {
        if (!SigningKey.isValidKid(kid)) {
            throw failure(property, "is not a valid kid: 1 to 64 characters of A-Z, a-z, 0-9, "
                    + "dot, underscore and hyphen");
        }
    }

    private static PrivateKey decodePrivateKey(String text, String property) {
        byte[] der = decodeBase64(text, property);
        try {
            return KeyFactory.getInstance(ED25519).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            // Not chained: see the class comment.
            throw failure(property, "is not a PKCS#8 Ed25519 private key");
        }
    }

    private static PublicKey decodePublicKey(String text, String property) {
        byte[] der = decodeBase64(text, property);
        try {
            return KeyFactory.getInstance(ED25519).generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw failure(property, "is not an X.509 Ed25519 public key");
        }
    }

    private static byte[] decodeBase64(String text, String property) {
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw failure(property, "is not valid standard Base64");
        }
    }

    private static void requireMatchingPair(PrivateKey privateKey, PublicKey publicKey) {
        byte[] probe = new byte[PROBE_LENGTH];
        new SecureRandom().nextBytes(probe);
        byte[] signature = Ed25519Signatures.sign(privateKey, probe);
        if (!Ed25519Signatures.verify(publicKey, probe, signature)) {
            throw failure(CURRENT_PUBLIC_KEY, "does not correspond to " + CURRENT_PRIVATE_KEY);
        }
    }

    private static IllegalStateException failure(String property, String problem) {
        return new IllegalStateException("the property " + property + " " + problem);
    }
}
