package com.confia.notifications.application.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Scenario NT05 (transactional-email-adapter design.md decision 4, step 1): a message is a template
 * of the closed list, one recipient and exactly the parameters that template declares, none of
 * which may carry a control character. The check runs at construction, so an invalid request never
 * reaches the transport and no connection is opened for it.
 */
class TransactionalEmailTest {

    private static final EmailRecipient ANA = new EmailRecipient("ana.martinez@colegio.edu.hn");
    private static final String LINK = "https://admin.confia.example/restablecer#token=SENTINEL-TOKEN";

    private static Map<String, String> validParameters() {
        return Map.of("link", LINK, "validityMinutes", "30");
    }

    @Test
    void theFirstTemplateDeclaresItsKeyVersionAndExactParameterNames() {
        TransactionalEmailTemplate template = TransactionalEmailTemplate.PASSWORD_RESET_LINK;

        assertThat(template.key()).isEqualTo("password-reset-link");
        assertThat(template.version()).isEqualTo(1);
        assertThat(template.parameterNames()).containsExactlyInAnyOrder("link", "validityMinutes");
        assertThat(TransactionalEmailTemplate.values()).hasSize(1);
    }

    @Test
    void everyTemplateKeyFitsTheShapeTheLogTableWillEnforce() {
        for (TransactionalEmailTemplate template : TransactionalEmailTemplate.values()) {
            assertThat(template.key()).matches("^[a-z][a-z0-9-]{0,62}$");
            assertThat(template.version()).isPositive();
        }
    }

    @Test
    void theTemplateParameterNamesCannotBeModifiedByACaller() {
        Set<String> names = TransactionalEmailTemplate.PASSWORD_RESET_LINK.parameterNames();

        assertThatThrownBy(() -> names.add("extra")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aMessageWithExactlyTheDeclaredParametersIsAccepted() {
        TransactionalEmail email = new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, validParameters());

        assertThat(email.template()).isEqualTo(TransactionalEmailTemplate.PASSWORD_RESET_LINK);
        assertThat(email.recipient()).isEqualTo(ANA);
        assertThat(email.parameters()).containsEntry("link", LINK).containsEntry("validityMinutes", "30");
    }

    @Test
    void theParametersAreCopiedSoALaterChangeToTheCallersMapHasNoEffect() {
        Map<String, String> source = new HashMap<>(validParameters());
        TransactionalEmail email = new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, source);

        source.put("link", "https://evil.example/");
        source.remove("validityMinutes");

        assertThat(email.parameters()).containsEntry("link", LINK).containsKey("validityMinutes");
        assertThatThrownBy(() -> email.parameters().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aMissingParameterIsRejectedByNameWithoutTheOtherValues() {
        Map<String, String> missing = Map.of("link", LINK);

        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("validityMinutes")
                .hasMessageNotContaining("SENTINEL-TOKEN");
    }

    @Test
    void anUnexpectedParameterIsRejectedWithoutEchoingItsCallerSuppliedNameOrValue() {
        Map<String, String> extra = new HashMap<>(validParameters());
        extra.put("bcc\r\nX", "intruso@example.com");

        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, extra))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not declare")
                .hasMessageNotContaining("bcc")
                .hasMessageNotContaining("intruso");
    }

    @Test
    void theHeaderInjectionOfScenarioNt05IsRejectedAsAnInvalidParameter() {
        Map<String, String> injected = Map.of("link", "https://admin.confia.example/\r\nBcc: intruso@example.com",
                "validityMinutes", "30");

        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, injected))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("link")
                .hasMessageNotContaining("intruso")
                .hasMessageNotContaining("Bcc");
    }

    @ParameterizedTest(name = "control character U+{0}")
    @ValueSource(ints = {0x00, 0x09, 0x0A, 0x0D, 0x1B, 0x7F, 0x85, 0x9F})
    void anyIsoControlCharacterInAParameterIsRejected(int codePoint) {
        Map<String, String> parameters = Map.of("link", "https://a.example/" + (char) codePoint + "x",
                "validityMinutes", "30");

        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, parameters))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankOrOversizedParameterAndNullPartsAreRejected() {
        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA,
                Map.of("link", "   ", "validityMinutes", "30")))
                .isInstanceOf(IllegalArgumentException.class);

        String longest = "h".repeat(TransactionalEmail.MAX_PARAMETER_LENGTH);
        assertThatCode(() -> new TransactionalEmail(TransactionalEmailTemplate.PASSWORD_RESET_LINK,
                ANA, Map.of("link", longest, "validityMinutes", "30"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA,
                Map.of("link", longest + "h", "validityMinutes", "30")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new TransactionalEmail(null, ANA, validParameters()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, null, validParameters()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theTextualRepresentationNamesTheTemplateButNotTheRecipientNorTheValues() {
        TransactionalEmail email = new TransactionalEmail(
                TransactionalEmailTemplate.PASSWORD_RESET_LINK, ANA, validParameters());

        assertThat(email.toString())
                .contains("password-reset-link")
                .doesNotContain("ana.martinez")
                .doesNotContain("colegio.edu.hn")
                .doesNotContain("SENTINEL-TOKEN")
                .doesNotContain("admin.confia.example");
    }
}
