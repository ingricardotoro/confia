package com.confia.bootstrap;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The command-line arguments each real process needs to start in a test, with no database and no
 * {@code application.yml} of its own in the test class path (web-edge-foundations design.md,
 * decision 2). A test {@code application.yml} would shadow the production one on the class path,
 * so everything a process needs beyond its defaults travels as an argument.
 *
 * <p>The two web processes bind to a random port. The administrative process also receives a
 * JDBC URL that nothing listens on, which is enough because the connection pool is lazy, and the
 * three secrets of decision 3 (the column-encryption master key, the Argon2id pepper and the login
 * institution id), generated at runtime, and an Ed25519 signing key pair generated the same way
 * (session-tokens-and-web-layer design.md, decision 3): no secret value and no key is ever written
 * in the repository (CLAUDE.md, regla 13).
 */
final class TestProcessArguments {

    /** The property the administrative process reads its master key from. */
    static final String MASTER_KEY_PROPERTY = "confia.crypto.column-master-key";

    /** The property the administrative process reads its Argon2id pepper from. */
    static final String PEPPER_PROPERTY = "confia.identity.argon2-pepper";

    /** The property the administrative process reads its login institution id from. */
    static final String INSTITUTION_PROPERTY = "confia.identity.login-institution-id";

    /** The kid of the signing key every administrative test process starts with. */
    static final String SIGNING_KID = "test-admin-key";

    /** The properties of the administrative signing key ring (design.md, decision 3). */
    static final String CURRENT_KID_PROPERTY = "confia.security.admin-signing.current.kid";
    static final String CURRENT_PRIVATE_KEY_PROPERTY =
            "confia.security.admin-signing.current.private-key";
    static final String CURRENT_PUBLIC_KEY_PROPERTY =
            "confia.security.admin-signing.current.public-key";
    static final String PREVIOUS_KID_PROPERTY = "confia.security.admin-signing.previous.kid";
    static final String PREVIOUS_PRIVATE_KEY_PROPERTY =
            "confia.security.admin-signing.previous.private-key";
    static final String PREVIOUS_PUBLIC_KEY_PROPERTY =
            "confia.security.admin-signing.previous.public-key";

    /** The reserved names of the portal's own private key (design.md, D-N2): never loaded. */
    static final String PORTAL_CURRENT_PRIVATE_KEY_PROPERTY =
            "confia.security.portal-signing.current.private-key";
    static final String PORTAL_PREVIOUS_PRIVATE_KEY_PROPERTY =
            "confia.security.portal-signing.previous.private-key";

    /** A JDBC URL no server answers on: port 1 on the loopback interface. */
    static final String UNREACHABLE_JDBC_URL =
            "jdbc:postgresql://127.0.0.1:1/confia-unreachable";

    private static final int MASTER_KEY_BYTES = 32;
    private static final int PEPPER_BYTES = 32;

    private TestProcessArguments() {
    }

    /** The arguments for {@code process}, a value of {@code APP_PROFILE}, plus any extra ones. */
    static String[] forProcess(String process, String... extra) {
        List<String> arguments = new ArrayList<>();
        if ("admin".equals(process)) {
            arguments.addAll(List.of(adminWith(Map.of())));
        } else if (!"worker".equals(process)) {
            arguments.add("--server.port=0");
        }
        arguments.addAll(List.of(extra));
        return arguments.toArray(String[]::new);
    }

    /** The administrative arguments with no master key, to prove the process refuses to start. */
    static String[] adminWithoutMasterKey() {
        return adminWithout(MASTER_KEY_PROPERTY);
    }

    /** The administrative arguments with the given value as the master key. */
    static String[] adminWithMasterKey(String value) {
        return adminWith(MASTER_KEY_PROPERTY, value);
    }

    /** The administrative arguments with {@code property} left out and every other one valid. */
    static String[] adminWithout(String property) {
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put(property, null);
        return adminWith(overrides);
    }

    /** The administrative arguments with {@code property} set to {@code value}, the rest valid. */
    static String[] adminWith(String property, String value) {
        return adminWith(Map.of(property, value));
    }

    /**
     * The complete administrative arguments: every property valid and freshly generated, then
     * {@code overrides} applied on top, a {@code null} value removing that property.
     */
    static String[] adminWith(Map<String, String> overrides) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("server.port", "0");
        properties.put("spring.datasource.url", UNREACHABLE_JDBC_URL);
        properties.put(MASTER_KEY_PROPERTY, newMasterKey());
        properties.put(PEPPER_PROPERTY, newPepper());
        properties.put(INSTITUTION_PROPERTY, UUID.randomUUID().toString());
        properties.putAll(newSigningKeyProperties());
        overrides.forEach((property, value) -> {
            if (value == null) {
                properties.remove(property);
            } else {
                properties.put(property, value);
            }
        });
        return properties.entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    /**
     * A signing key pair made now, with {@code KeyPairGenerator.getInstance("Ed25519")}, as the
     * three properties of the current key: the kid, the PKCS#8 private key and the X.509 public
     * key, both in standard Base64. Different on every call; nothing of it is ever written down
     * (CLAUDE.md, regla 13).
     */
    static Map<String, String> newSigningKeyProperties() {
        KeyPair pair = newEd25519Pair();
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(CURRENT_KID_PROPERTY, SIGNING_KID);
        properties.put(CURRENT_PRIVATE_KEY_PROPERTY,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        properties.put(CURRENT_PUBLIC_KEY_PROPERTY,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
        return properties;
    }

    /** The X.509 public key of a fresh pair in Base64, for a previous key of a rotation. */
    static String newPublicKey() {
        return Base64.getEncoder().encodeToString(newEd25519Pair().getPublic().getEncoded());
    }

    /**
     * Standard Base64 that decodes but is not a key of any kind. Fixed, and not random, so that
     * a test asserting "no fragment of the value in the trace" cannot fail by chance.
     */
    static String base64ThatIsNotAKey() {
        return Base64.getEncoder().encodeToString(
                "this text decodes but it is not a key".getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * A well-formed PKCS#8 envelope of an Ed448 private key (algorithm identifier 1.3.101.113)
     * around a fixed, patterned 57-byte body that is not a real key. A parser that only checked
     * the envelope would accept it; the loader must refuse it because it is not Ed25519.
     */
    static String ed448ShapedPrivateKeyBase64() {
        byte[] header = {0x30, 0x47, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x71,
                0x04, 0x3b, 0x04, 0x39};
        byte[] der = new byte[header.length + 57];
        System.arraycopy(header, 0, der, 0, header.length);
        for (int i = header.length; i < der.length; i++) {
            der[i] = (byte) (i * 37 + 11);
        }
        return Base64.getEncoder().encodeToString(der);
    }

    private static KeyPair newEd25519Pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fresh random 32-byte key in Base64, different on every call. */
    static String newMasterKey() {
        return randomBase64(MASTER_KEY_BYTES);
    }

    /** A fresh random 32-byte Argon2id pepper in Base64, different on every call. */
    static String newPepper() {
        return randomBase64(PEPPER_BYTES);
    }

    private static String randomBase64(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
