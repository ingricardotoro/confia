package com.confia.notifications.application.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A recipient is one validated address whose textual form is redacted (design.md decision 1;
 * docs/08: the address is personal data and exists only in memory while the message is sent).
 */
class EmailRecipientTest {

    @Test
    void aWellFormedAddressIsKeptAndSurroundingBlanksAreStripped() {
        assertThat(new EmailRecipient("  Ana.Martinez@Colegio.edu.hn \n").address())
                .isEqualTo("Ana.Martinez@Colegio.edu.hn");
    }

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {"a@b.co", "first.last+tag@sub.domain.example", "o'brien@colegio.edu.hn",
            "x_y-z@my-school.edu.hn"})
    void commonAddressShapesAreAccepted(String address) {
        assertThatCode(() -> new EmailRecipient(address)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "rejects {0}")
    @ValueSource(strings = {"", "   ", "no-at-sign", "two@@signs.example", "a@b@c.example", "@no-local.example",
            "no-domain@", "no-dot@localhost", "spaces in@local.example", "angle<brackets>@x.example",
            "comma,separated@x.example", "semi;colon@x.example", "quote\"d@x.example",
            "a@b.example\r\nBcc: intruso@example.com", "a@b.example\nBcc: x@y.z", "a@-bad.example",
            "a@bad-.example", "a@dots..example", ".dot@x.example"})
    void anInvalidAddressOrOneWithAnInjectedHeaderIsRejectedWithoutEchoingIt(String address) {
        assertThatThrownBy(() -> new EmailRecipient(address))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("intruso")
                .hasMessageNotContaining("no-domain");
    }

    @Test
    void theLengthLimitsOfRfc5321AreEnforced() {
        String local64 = "l".repeat(64);
        assertThatCode(() -> new EmailRecipient(local64 + "@x.example")).doesNotThrowAnyException();
        assertThatThrownBy(() -> new EmailRecipient(local64 + "l@x.example"))
                .isInstanceOf(IllegalArgumentException.class);

        String label63 = "d".repeat(63);
        assertThatCode(() -> new EmailRecipient("a@" + label63 + ".example")).doesNotThrowAnyException();
        assertThatThrownBy(() -> new EmailRecipient("a@" + label63 + "d.example"))
                .isInstanceOf(IllegalArgumentException.class);

        String longest = local64 + "@" + label63 + "." + label63 + "." + "d".repeat(61);
        assertThat(longest).hasSize(254);
        assertThatCode(() -> new EmailRecipient(longest)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new EmailRecipient(longest + "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullAddressIsRejected() {
        assertThatThrownBy(() -> new EmailRecipient(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void theTextualRepresentationRevealsNeitherTheLocalPartNorTheDomain() {
        assertThat(new EmailRecipient("ana.martinez@colegio.edu.hn").toString())
                .doesNotContain("ana")
                .doesNotContain("colegio")
                .doesNotContain("@")
                .contains("redacted");
    }

    @Test
    void twoRecipientsWithTheSameAddressAreEqual() {
        assertThat(new EmailRecipient("a@b.co")).isEqualTo(new EmailRecipient(" a@b.co "))
                .hasSameHashCodeAs(new EmailRecipient("a@b.co"));
        assertThat(new EmailRecipient("a@b.co")).isNotEqualTo(new EmailRecipient("c@b.co"));
    }
}
