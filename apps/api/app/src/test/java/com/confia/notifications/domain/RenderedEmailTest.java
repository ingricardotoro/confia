package com.confia.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A composed message: a one-line subject and a plain-text body, neither of which is ever logged. */
class RenderedEmailTest {

    @Test
    void aSubjectAndAMultiLineBodyAreAccepted() {
        RenderedEmail email = new RenderedEmail("Restablece tu contraseña",
                "Hola.\r\nUsa este enlace:\n\thttps://admin.confia.example/restablecer#token=T\n");

        assertThat(email.subject()).isEqualTo("Restablece tu contraseña");
        assertThat(email.body()).contains("#token=T");
    }

    @ParameterizedTest(name = "subject {0}")
    @ValueSource(strings = {"line1\nBcc: x@y.z", "line1\rBcc: x@y.z", "tab\there", "nul\u0000",
            "del\u007f", "c1\u0085"})
    void aSubjectWithAControlCharacterIsRejectedWithoutEchoingIt(String subject) {
        assertThatThrownBy(() -> new RenderedEmail(subject, "body"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("Bcc")
                .hasMessageContaining("subject");
    }

    @ParameterizedTest(name = "body {0}")
    @ValueSource(strings = {"nul\u0000", "bell\u0007", "del\u007f", "c1\u0085"})
    void aBodyWithAControlCharacterOtherThanLineBreaksAndTabsIsRejected(String body) {
        assertThatThrownBy(() -> new RenderedEmail("subject", body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("body");
    }

    @Test
    void blankPartsAndNullsAreRejected() {
        assertThatThrownBy(() -> new RenderedEmail("  ", "body"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenderedEmail("subject", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RenderedEmail(null, "body"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RenderedEmail("subject", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theSubjectIsBoundedSoAHeaderStaysShort() {
        assertThatCode(() -> new RenderedEmail("s".repeat(RenderedEmail.MAX_SUBJECT_LENGTH), "b"))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                () -> new RenderedEmail("s".repeat(RenderedEmail.MAX_SUBJECT_LENGTH + 1), "b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTextualRepresentationRevealsNeitherTheSubjectNorTheBody() {
        RenderedEmail email = new RenderedEmail("SENTINEL-SUBJECT", "SENTINEL-BODY-TOKEN");

        assertThat(email.toString()).doesNotContain("SENTINEL").contains("redacted");
    }
}
