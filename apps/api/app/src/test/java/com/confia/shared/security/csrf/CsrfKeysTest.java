package com.confia.shared.security.csrf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Specs/web-edge, "La clave CSRF es un secreto de entorno exclusivo del proceso administrativo"
 * (design.md, decision 3): the keys are read with {@code Environment.getProperty}, and a missing,
 * short, non-Base64 or repeated key stops the process with an {@link IllegalStateException} that
 * names the property, repeats nothing of the value and chains no cause. The accepted values are
 * made for the run; the rejected non-Base64 text is made of symbols no message of the code could
 * contain.
 */
class CsrfKeysTest {

    private static final String KEY = "confia.security.csrf.key";
    private static final String PREVIOUS = "confia.security.csrf.previous-key";

    /** Not a secret and not valid Base64: symbols, so a fragment in a message can only come from it. */
    private static final String NOT_BASE64 = "Qx7!Rk2#Jv9%Wm5@";

    @Test
    void theCurrentKeyOf32BytesIsLoadedAndThereIsNoPreviousKey() {
        byte[] bytes = CsrfKeyFixtures.newKey(32);

        CsrfKeys keys = CsrfKeys.load(
                new MockEnvironment().withProperty(KEY, CsrfKeyFixtures.base64(bytes)));

        assertThat(keys.current()).isEqualTo(bytes);
        assertThat(keys.previous()).isEmpty();
    }

    @Test
    void aLongerKeyIsAccepted() {
        byte[] bytes = CsrfKeyFixtures.newKey(64);

        CsrfKeys keys = CsrfKeys.load(
                new MockEnvironment().withProperty(KEY, CsrfKeyFixtures.base64(bytes)));

        assertThat(keys.current()).isEqualTo(bytes);
    }

    @Test
    void thePreviousKeyIsLoadedWhenItIsSet() {
        byte[] current = CsrfKeyFixtures.newKey();
        byte[] previous = CsrfKeyFixtures.newKey();

        CsrfKeys keys = CsrfKeys.load(new MockEnvironment()
                .withProperty(KEY, CsrfKeyFixtures.base64(current))
                .withProperty(PREVIOUS, CsrfKeyFixtures.base64(previous)));

        assertThat(keys.current()).isEqualTo(current);
        assertThat(keys.previous()).hasValueSatisfying(value -> assertThat(value).isEqualTo(previous));
    }

    @Test
    void aBlankPreviousKeyIsTreatedAsAbsent() {
        CsrfKeys keys = CsrfKeys.load(new MockEnvironment()
                .withProperty(KEY, CsrfKeyFixtures.base64(CsrfKeyFixtures.newKey()))
                .withProperty(PREVIOUS, "   "));

        assertThat(keys.previous()).isEmpty();
    }

    @Test
    void aMissingKeyStopsTheLoadNamingTheProperty() {
        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY)
                .hasNoCause();
    }

    @Test
    void aBlankKeyIsTreatedAsMissing() {
        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment().withProperty(KEY, "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY)
                .hasNoCause();
    }

    @Test
    void aKeyShorterThan32BytesStopsTheLoadWithoutRepeatingIt() {
        String value = CsrfKeyFixtures.base64(CsrfKeyFixtures.newKey(16));

        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment().withProperty(KEY, value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY)
                .hasMessageContaining("32")
                .hasMessageNotContaining(value)
                .hasNoCause();
    }

    @Test
    void theBoundaryIsExactly32Bytes() {
        byte[] almost = CsrfKeyFixtures.newKey(31);

        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment()
                .withProperty(KEY, CsrfKeyFixtures.base64(almost))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CsrfKeys.load(new MockEnvironment()
                .withProperty(KEY, CsrfKeyFixtures.base64(CsrfKeyFixtures.newKey(32)))).current())
                .hasSize(32);
    }

    @Test
    void aKeyThatIsNotBase64StopsTheLoadWithoutRepeatingAnyPartOfIt() {
        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment().withProperty(KEY, NOT_BASE64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(KEY)
                .satisfies(failure -> {
                    assertThat(failure.getCause()).isNull();
                    for (int start = 0; start + 4 <= NOT_BASE64.length(); start++) {
                        assertThat(failure.getMessage())
                                .doesNotContain(NOT_BASE64.substring(start, start + 4));
                    }
                });
    }

    @Test
    void aPreviousKeyThatIsNotBase64NamesThePreviousProperty() {
        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment()
                .withProperty(KEY, CsrfKeyFixtures.base64(CsrfKeyFixtures.newKey()))
                .withProperty(PREVIOUS, NOT_BASE64)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREVIOUS)
                .hasMessageNotContaining(NOT_BASE64)
                .hasNoCause();
    }

    @Test
    void aShortPreviousKeyNamesThePreviousProperty() {
        String value = CsrfKeyFixtures.base64(CsrfKeyFixtures.newKey(16));

        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment()
                .withProperty(KEY, CsrfKeyFixtures.base64(CsrfKeyFixtures.newKey()))
                .withProperty(PREVIOUS, value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREVIOUS)
                .hasMessageNotContaining(value)
                .hasNoCause();
    }

    @Test
    void aPreviousKeyEqualToTheCurrentOneStopsTheLoadNamingBothProperties() {
        String value = CsrfKeyFixtures.base64(CsrfKeyFixtures.newKey());

        assertThatThrownBy(() -> CsrfKeys.load(new MockEnvironment()
                .withProperty(KEY, value).withProperty(PREVIOUS, value)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PREVIOUS)
                .hasMessageContaining(KEY)
                .hasMessageNotContaining(value)
                .hasNoCause();
    }

    @Test
    void ofRefusesAKeyThatIsTooShortAndAPreviousEqualToTheCurrent() {
        byte[] key = CsrfKeyFixtures.newKey();

        assertThatThrownBy(() -> CsrfKeys.of(CsrfKeyFixtures.newKey(31), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsrfKeys.of(key, CsrfKeyFixtures.newKey(31)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsrfKeys.of(key, key.clone()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theKeysAreCopiedSoALaterChangeToTheCallersArrayDoesNotChangeThem() {
        byte[] key = CsrfKeyFixtures.newKey();
        byte[] original = key.clone();
        CsrfKeys keys = CsrfKeys.of(key, null);

        Arrays.fill(key, (byte) 0);

        assertThat(keys.current()).isEqualTo(original);
        keys.current()[0] ^= 1;
        assertThat(keys.current()).as("the accessor returns a copy").isEqualTo(original);
    }
}
