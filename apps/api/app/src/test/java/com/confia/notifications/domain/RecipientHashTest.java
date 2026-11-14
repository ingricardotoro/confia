package com.confia.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The value type of the recipient hash (design.md decision 8): 64 lowercase hex characters, and the
 * one normalization every hash input goes through. The HMAC itself is computed in {@code
 * notifications.application} with {@code shared.security.Digests}; the domain holds no crypto.
 */
class RecipientHashTest {

    @Test
    void normalizationStripsBlanksAndLowerCasesWithTheRootLocale() {
        assertThat(RecipientHash.normalize("  Ana.Martinez@Colegio.edu.hn \t"))
                .isEqualTo("ana.martinez@colegio.edu.hn");
        assertThat(RecipientHash.normalize("\n ANA@X.HN\n")).isEqualTo("ana@x.hn");
    }

    @Test
    void normalizationDoesNotDependOnTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(RecipientHash.normalize("INFO@COLEGIO.EDU.HN"))
                    .isEqualTo("info@colegio.edu.hn");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void aNullAddressIsRejected() {
        assertThatThrownBy(() -> RecipientHash.normalize(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aValueThatIsNotLowerCaseHex64IsRejectedWithoutEchoingIt() {
        assertThatThrownBy(() -> new RecipientHash("A".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("AAAA");
        assertThatThrownBy(() -> new RecipientHash("a".repeat(63)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecipientHash("a".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecipientHash(null)).isInstanceOf(NullPointerException.class);
        assertThatCode(() -> new RecipientHash("0".repeat(64))).doesNotThrowAnyException();
    }
}
