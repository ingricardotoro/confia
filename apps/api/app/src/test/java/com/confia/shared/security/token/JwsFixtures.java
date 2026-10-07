package com.confia.shared.security.token;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/**
 * What the token tests need and the production code must not offer: key pairs generated at run time
 * (never a key in the repository, CLAUDE.md regla 13) and a signer that builds a token from raw,
 * hand-written header and payload text, so that an attack can be a valid signature over a forged
 * header or over a non-canonical segment. It signs with the JDK's {@link Signature} directly and
 * never through the code under test, so a defect in {@code Ed25519Signatures} cannot hide in the
 * test that exercises it.
 */
final class JwsFixtures {

    static final String CURRENT_KID = "admin-2026a";
    static final String PREVIOUS_KID = "admin-2026b";

    /** The fixed DER prefix of an X.509 SubjectPublicKeyInfo that wraps 32 raw Ed25519 bytes. */
    private static final byte[] ED25519_X509_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b,
            0x65, 0x70, 0x03, 0x21, 0x00};

    /** The order of the Ed25519 group: 2^252 + 27742317777372353535851937790883648493. */
    private static final BigInteger GROUP_ORDER = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    private JwsFixtures() {
    }

    static KeyPair newPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String canonicalHeaderJson(String kid) {
        return "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\"}";
    }

    static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] jdkSign(PrivateKey key, String signingInput) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token whose three segments are exactly the given texts, signed over the first two. */
    static String tokenOverSegments(String headerSegment, String payloadSegment, PrivateKey signer) {
        String signingInput = headerSegment + "." + payloadSegment;
        return signingInput + "." + b64(jdkSign(signer, signingInput));
    }

    /** A token with a correct signature over the given raw header and payload texts. */
    static String token(String headerJson, String payloadJson, PrivateKey signer) {
        return tokenOverSegments(b64(headerJson), b64(payloadJson), signer);
    }

    /** The token the system itself would issue, built without the code under test. */
    static String issuedToken(String kid, String payloadJson, PrivateKey signer) {
        return token(canonicalHeaderJson(kid), payloadJson, signer);
    }

    static String withSignature(String token, byte[] signature) {
        return token.substring(0, token.lastIndexOf('.') + 1) + b64(signature);
    }

    static byte[] signatureOf(String token) {
        return Base64.getUrlDecoder().decode(token.substring(token.lastIndexOf('.') + 1));
    }

    /** The signature with {@code S + L} in place of {@code S}: still 64 bytes, never canonical. */
    static byte[] malleableVersionOf(byte[] signature) {
        BigInteger scalar = littleEndian(Arrays.copyOfRange(signature, 32, 64)).add(GROUP_ORDER);
        byte[] bigEndian = scalar.toByteArray();
        byte[] result = signature.clone();
        Arrays.fill(result, 32, 64, (byte) 0);
        for (int i = 0; i < 32 && i < bigEndian.length; i++) {
            result[32 + i] = bigEndian[bigEndian.length - 1 - i];
        }
        return result;
    }

    static BigInteger littleEndian(byte[] bytes) {
        byte[] reversed = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            reversed[i] = bytes[bytes.length - 1 - i];
        }
        return new BigInteger(1, reversed);
    }

    static BigInteger groupOrder() {
        return GROUP_ORDER;
    }

    static PublicKey publicKeyFromRaw(byte[] raw) {
        byte[] encoded = Arrays.copyOf(ED25519_X509_PREFIX, ED25519_X509_PREFIX.length + raw.length);
        System.arraycopy(raw, 0, encoded, ED25519_X509_PREFIX.length, raw.length);
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * RFC 8037 Appendix A.4: the public key, the signing input and the published signature. The
     * private key of the appendix is not in the repository.
     */
    record RfcVector(PublicKey publicKey, String signingInput, byte[] signature) {

        static RfcVector load() {
            try (InputStream in = JwsFixtures.class
                    .getResourceAsStream("/rfc8037/ed25519-a4.properties")) {
                Properties properties = new Properties();
                properties.load(in);
                return new RfcVector(
                        publicKeyFromRaw(Base64.getUrlDecoder().decode(properties.getProperty("publicKey"))),
                        properties.getProperty("signingInput"),
                        Base64.getUrlDecoder().decode(properties.getProperty("signature")));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        byte[] signingInputBytes() {
            return signingInput.getBytes(StandardCharsets.US_ASCII);
        }

        /** The complete compact token of the appendix, header {@code {"alg":"EdDSA"}} included. */
        String compactToken() {
            return signingInput + "." + b64(signature);
        }
    }
}
