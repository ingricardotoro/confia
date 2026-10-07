package com.confia.shared.security.token;

import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.EdECKey;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One Ed25519 key of the ring, named by its kid (session-tokens-and-web-layer design.md, decision 3).
 * A key either signs and verifies, or only verifies: the previous key of a rotation never has a
 * private key. The kid is restricted to a closed alphabet so that it can be written into the
 * canonical header without any escaping, and it is never anything an attacker supplies.
 */
public final class SigningKey {

    private static final Pattern KID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final String ED25519 = "Ed25519";

    private final String kid;
    private final PublicKey publicKey;
    private final PrivateKey privateKey;

    private SigningKey(String kid, PublicKey publicKey, PrivateKey privateKey) {
        if (!isValidKid(Objects.requireNonNull(kid, "kid"))) {
            throw new IllegalArgumentException("the kid is outside [A-Za-z0-9._-]{1,64}");
        }
        this.kid = kid;
        this.publicKey = requireEd25519(publicKey);
        this.privateKey = privateKey == null ? null : requireEd25519(privateKey);
    }

    /** A key that signs and verifies. */
    public static SigningKey signing(String kid, PublicKey publicKey, PrivateKey privateKey) {
        return new SigningKey(kid, publicKey, Objects.requireNonNull(privateKey, "privateKey"));
    }

    /** A key that only verifies, like the previous key of a rotation. */
    public static SigningKey verifyOnly(String kid, PublicKey publicKey) {
        return new SigningKey(kid, publicKey, null);
    }

    /** Whether the text is a kid: 1 to 64 characters of {@code A-Z a-z 0-9 . _ -}. */
    static boolean isValidKid(String kid) {
        return KID.matcher(kid).matches();
    }

    public String kid() {
        return kid;
    }

    public PublicKey publicKey() {
        return publicKey;
    }

    public boolean hasPrivateKey() {
        return privateKey != null;
    }

    /** Package-private: only the codec signs. */
    PrivateKey privateKey() {
        return privateKey;
    }

    private static <K extends Key> K requireEd25519(K key) {
        if (!(Objects.requireNonNull(key, "key") instanceof EdECKey edKey)
                || !ED25519.equals(edKey.getParams().getName())) {
            throw new IllegalArgumentException("the key is not an Ed25519 key");
        }
        return key;
    }

    @Override
    public String toString() {
        return "SigningKey[kid=" + kid + "]";
    }
}
