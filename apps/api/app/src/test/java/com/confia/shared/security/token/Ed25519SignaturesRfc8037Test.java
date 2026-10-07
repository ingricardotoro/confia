package com.confia.shared.security.token;

import static com.confia.shared.security.token.JwsFixtures.groupOrder;
import static com.confia.shared.security.token.JwsFixtures.littleEndian;
import static com.confia.shared.security.token.JwsFixtures.malleableVersionOf;
import static com.confia.shared.security.token.JwsFixtures.newPair;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.shared.security.token.JwsFixtures.RfcVector;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The signature primitive against RFC 8037 Appendix A.4 and against key pairs generated at run time
 * (session-tokens-and-web-layer, scenarios I46 and I138; design.md section 2.1, probes S-1 and S-2).
 *
 * <p>The appendix vector is read from {@code rfc8037/ed25519-a4.properties}, which holds the public
 * key, the signing input and the published signature and never the private key (CLAUDE.md regla 13).
 */
class Ed25519SignaturesRfc8037Test {

    private static final RfcVector VECTOR = RfcVector.load();

    @Test
    void thePublishedRfcSignatureVerifiesWithThePublicKeyOfTheAppendix() {
        assertThat(VECTOR.signature()).hasSize(64);
        assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), VECTOR.signingInputBytes(),
                VECTOR.signature())).isTrue();
    }

    @Test
    void thePublishedSignatureWithAnyOneBitChangedIsRejected() {
        for (int bit = 0; bit < VECTOR.signature().length * 8; bit++) {
            byte[] altered = VECTOR.signature().clone();
            altered[bit / 8] ^= (byte) (1 << (bit % 8));

            assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), VECTOR.signingInputBytes(), altered))
                    .as("signature with bit %d flipped", bit).isFalse();
        }
    }

    @Test
    void thePublishedSignatureDoesNotVerifyAnAlteredMessage() {
        byte[] altered = VECTOR.signingInputBytes();
        altered[altered.length - 1] ^= 1;

        assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), altered, VECTOR.signature())).isFalse();
    }

    @Test
    void aGeneratedKeySignsDeterministicallyAndVerifiesButNotWithTheRfcKey() {
        KeyPair pair = newPair();

        byte[] first = Ed25519Signatures.sign(pair.getPrivate(), VECTOR.signingInputBytes());
        byte[] second = Ed25519Signatures.sign(pair.getPrivate(), VECTOR.signingInputBytes());

        assertThat(first).hasSize(64).isEqualTo(second);
        assertThat(first).isEqualTo(JwsFixtures.jdkSign(pair.getPrivate(), VECTOR.signingInput()));
        assertThat(Ed25519Signatures.verify(pair.getPublic(), VECTOR.signingInputBytes(), first))
                .isTrue();
        assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), VECTOR.signingInputBytes(), first))
                .isFalse();
    }

    @Test
    void twoGeneratedKeysSignTheSameInputDifferentlyAndDoNotVerifyEachOther() {
        KeyPair one = newPair();
        KeyPair other = newPair();

        byte[] signedByOne = Ed25519Signatures.sign(one.getPrivate(), VECTOR.signingInputBytes());
        byte[] signedByOther = Ed25519Signatures.sign(other.getPrivate(), VECTOR.signingInputBytes());

        assertThat(signedByOne).isNotEqualTo(signedByOther);
        assertThat(Ed25519Signatures.verify(other.getPublic(), VECTOR.signingInputBytes(), signedByOne))
                .isFalse();
        assertThat(Ed25519Signatures.verify(one.getPublic(), VECTOR.signingInputBytes(), signedByOne))
                .isTrue();
    }

    @Test
    void theJdkThrowsForAMalleableSignatureAndForALengthOf63BytesAndThePrimitiveAnswersFalse() {
        byte[] malleable = malleableVersionOf(VECTOR.signature());
        byte[] shortSignature = Arrays.copyOf(VECTOR.signature(), 63);

        // Probe S-2 (design.md section 2.1): the JDK does not answer false, it throws.
        assertThatThrownBy(() -> jdkVerify(malleable)).isInstanceOf(SignatureException.class);
        assertThatThrownBy(() -> jdkVerify(shortSignature)).isInstanceOf(SignatureException.class);

        assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), VECTOR.signingInputBytes(), malleable))
                .isFalse();
        assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), VECTOR.signingInputBytes(),
                shortSignature)).isFalse();
    }

    @Test
    void aSignatureOf65BytesOrOfNoBytesIsFalseWithoutAnException() {
        byte[] long65 = Arrays.copyOf(VECTOR.signature(), 65);

        assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), VECTOR.signingInputBytes(), long65))
                .isFalse();
        assertThat(Ed25519Signatures.verify(VECTOR.publicKey(), VECTOR.signingInputBytes(),
                new byte[0])).isFalse();
    }

    @Test
    void theMalleableSignatureIsTheOriginalWithTheGroupOrderAddedToItsScalar() {
        byte[] malleable = malleableVersionOf(VECTOR.signature());

        assertThat(malleable).hasSize(64);
        assertThat(Arrays.copyOf(malleable, 32)).isEqualTo(Arrays.copyOf(VECTOR.signature(), 32));
        assertThat(littleEndian(Arrays.copyOfRange(malleable, 32, 64)))
                .isEqualTo(littleEndian(Arrays.copyOfRange(VECTOR.signature(), 32, 64))
                        .add(groupOrder()));
    }

    private static boolean jdkVerify(byte[] signature) throws GeneralSecurityException {
        Signature jdk = Signature.getInstance("Ed25519");
        jdk.initVerify(VECTOR.publicKey());
        jdk.update(VECTOR.signingInput().getBytes(StandardCharsets.US_ASCII));
        return jdk.verify(signature);
    }
}
