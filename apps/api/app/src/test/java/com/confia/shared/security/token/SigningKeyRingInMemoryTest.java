package com.confia.shared.security.token;

import static com.confia.shared.security.token.JwsFixtures.CURRENT_KID;
import static com.confia.shared.security.token.JwsFixtures.PREVIOUS_KID;
import static com.confia.shared.security.token.JwsFixtures.b64;
import static com.confia.shared.security.token.JwsFixtures.canonicalHeaderJson;
import static com.confia.shared.security.token.JwsFixtures.newPair;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The in-memory shape of the key ring that the codec needs (session-tokens-and-web-layer tasks.md,
 * task 1.1, "Nota de costura"): one key that signs, optionally one that only verifies, a kid that can
 * never break the canonical header it is written into, and the header segment precomputed per kid.
 * The loader from the environment and the startup guard belong to the next change (task 1.2).
 */
class SigningKeyRingInMemoryTest {

    @Test
    void theRingSignsWithTheCurrentKeyAndKnowsTheCanonicalHeaderOfEveryKid() {
        KeyPair current = newPair();
        KeyPair previous = newPair();

        SigningKeyRing ring = SigningKeyRing.of(
                SigningKey.signing(CURRENT_KID, current.getPublic(), current.getPrivate()),
                SigningKey.verifyOnly(PREVIOUS_KID, previous.getPublic()));

        assertThat(ring.signingKey().kid()).isEqualTo(CURRENT_KID);
        assertThat(ring.currentHeaderSegment()).isEqualTo(b64(canonicalHeaderJson(CURRENT_KID)));
        assertThat(ring.keyForHeaderSegment(b64(canonicalHeaderJson(CURRENT_KID)))
                .map(SigningKey::kid)).hasValue(CURRENT_KID);
        assertThat(ring.keyForHeaderSegment(b64(canonicalHeaderJson(PREVIOUS_KID)))
                .map(SigningKey::kid)).hasValue(PREVIOUS_KID);
        assertThat(ring.keyForHeaderSegment(b64(canonicalHeaderJson("other")))).isEmpty();
    }

    @Test
    void aRingWithOnlyTheCurrentKeyKnowsOnlyOneHeader() {
        KeyPair current = newPair();

        SigningKeyRing ring = SigningKeyRing.of(
                SigningKey.signing(CURRENT_KID, current.getPublic(), current.getPrivate()));

        assertThat(ring.keyForHeaderSegment(ring.currentHeaderSegment())).isPresent();
        assertThat(ring.keyForHeaderSegment(b64(canonicalHeaderJson(PREVIOUS_KID)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "a\"b", "a\\b", "a,b", "a}b", "{kid}", "kid\n", "kéy",
            "a/b", "a:b"})
    void aKidOutsideTheClosedFormatIsRejected(String kid) {
        KeyPair pair = newPair();

        assertThatThrownBy(() -> SigningKey.verifyOnly(kid, pair.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKidOf64CharactersIsAcceptedAndOneOf65IsRejected() {
        KeyPair pair = newPair();

        assertThat(SigningKey.verifyOnly("k".repeat(64), pair.getPublic()).kid()).hasSize(64);
        assertThatThrownBy(() -> SigningKey.verifyOnly("k".repeat(65), pair.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKidAlphabetIsLettersDigitsDotUnderscoreAndHyphen() {
        KeyPair pair = newPair();

        assertThat(SigningKey.verifyOnly("Az09._-", pair.getPublic()).kid()).isEqualTo("Az09._-");
    }

    @Test
    void theRingRefusesTwoKeysWithTheSameKid() {
        KeyPair current = newPair();
        KeyPair previous = newPair();

        assertThatThrownBy(() -> SigningKeyRing.of(
                SigningKey.signing(CURRENT_KID, current.getPublic(), current.getPrivate()),
                SigningKey.verifyOnly(CURRENT_KID, previous.getPublic())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCurrentKeyMustSignAndThePreviousKeyMustNeverHoldAPrivateKey() {
        KeyPair current = newPair();
        KeyPair previous = newPair();

        assertThatThrownBy(() -> SigningKeyRing.of(
                SigningKey.verifyOnly(CURRENT_KID, current.getPublic())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningKeyRing.of(
                SigningKey.signing(CURRENT_KID, current.getPublic(), current.getPrivate()),
                SigningKey.signing(PREVIOUS_KID, previous.getPublic(), previous.getPrivate())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyThatIsNotAnEd25519KeyIsRejected() throws NoSuchAlgorithmException {
        KeyPair ed448 = KeyPairGenerator.getInstance("Ed448").generateKeyPair();
        KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        KeyPair ed25519 = newPair();

        assertThatThrownBy(() -> SigningKey.verifyOnly(CURRENT_KID, ed448.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningKey.verifyOnly(CURRENT_KID, rsa.getPublic()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningKey.signing(CURRENT_KID, ed25519.getPublic(), ed448.getPrivate()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SigningKey.signing(CURRENT_KID, ed25519.getPublic(), rsa.getPrivate()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyNeverPrintsItsKeyMaterial() {
        KeyPair pair = newPair();
        SigningKey key = SigningKey.signing(CURRENT_KID, pair.getPublic(), pair.getPrivate());

        assertThat(key.toString()).isEqualTo("SigningKey[kid=admin-2026a]");
        assertThat(key.hasPrivateKey()).isTrue();
        assertThat(SigningKey.verifyOnly(PREVIOUS_KID, pair.getPublic()).hasPrivateKey()).isFalse();
    }
}
