package com.confia.guardians.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.guardians.domain.GuardianFieldCheck.Problem;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each field rule of a guardian row of an import, with the stable reason and never the value. */
class GuardianFieldCheckTest {

    private static List<Problem> check(String ref, String first, String last, String doc, String rtn, String email,
            String phone, String address, String channel) {
        return GuardianFieldCheck.problems(ref, first, last, doc, rtn, email, phone, address, channel);
    }

    private static List<Problem> valid() {
        return check("P1", "Rosa", "Aguilar", null, null, null, null, null, null);
    }

    @Test
    void aRowWithOnlyTheRequiredColumnsHasNoProblem() {
        assertThat(valid()).isEmpty();
        assertThat(check(" P1 ", "Rosa", "Aguilar", "0801-1980-12345", "0801-1980-123456", "rosa@example.org",
                "+504 9999-9999", "Col. Centro", "EMAIL")).isEmpty();
    }

    @Test
    void theRequiredFieldsAreRequired() {
        assertThat(check(null, " ", "", "", "", "", "", "", "")).containsExactly(
                new Problem("legacyId", "required"), new Problem("firstName", "required"),
                new Problem("lastName", "required"));
    }

    @Test
    void controlCharactersAndLengthsAreRefused() {
        assertThat(check("P\t1", "Ro\nsa", "A".repeat(101), null, null, null, null, null, null)).containsExactly(
                new Problem("legacyId", "invalid-format"), new Problem("firstName", "invalid-format"),
                new Problem("lastName", "too-long"));
        assertThat(check("P".repeat(65), "Rosa", "Aguilar", null, null, null, null, null, null))
                .containsExactly(new Problem("legacyId", "too-long"));
    }

    @Test
    void theDocumentIsTooLongOrWronglyFormedOrHasControlCharacters() {
        assertThat(check("P1", "Rosa", "Aguilar", "1".repeat(21), null, null, null, null, null))
                .containsExactly(new Problem("nationalId", "too-long"));
        assertThat(check("P1", "Rosa", "Aguilar", "12 34", null, null, null, null, null))
                .containsExactly(new Problem("nationalId", "invalid-format"));
        assertThat(check("P1", "Rosa", "Aguilar", "0801\t1980", null, null, null, null, null))
                .containsExactly(new Problem("nationalId", "invalid-format"));
    }

    @Test
    void theRtnIsFourteenDigits() {
        assertThat(check("P1", "Rosa", "Aguilar", null, "123", null, null, null, null))
                .containsExactly(new Problem("rtn", "invalid-format"));
        assertThat(check("P1", "Rosa", "Aguilar", null, "0801-1980-123456", null, null, null, null)).isEmpty();
        assertThat(check("P1", "Rosa", "Aguilar", null, "1".repeat(21), null, null, null, null))
                .containsExactly(new Problem("rtn", "too-long"));
    }

    @Test
    void theContactDataIsValidatedWithTheRulesOfTheRegistry() {
        assertThat(check("P1", "Rosa", "Aguilar", null, null, "no-at-sign", "abc", "A".repeat(301), null))
                .containsExactly(new Problem("email", "invalid-format"), new Problem("phone", "invalid-format"),
                        new Problem("address", "too-long"));
        assertThat(check("P1", "Rosa", "Aguilar", null, null, "a@b." + "c".repeat(260), "1".repeat(21), null, null))
                .containsExactly(new Problem("email", "too-long"), new Problem("phone", "too-long"));
    }

    @Test
    void theChannelIsOneOfTheRegistryOrBlank() {
        assertThat(check("P1", "Rosa", "Aguilar", null, null, null, null, null, "WHATSAPP")).isEmpty();
        assertThat(check("P1", "Rosa", "Aguilar", null, null, null, null, null, "  ")).isEmpty();
        assertThat(check("P1", "Rosa", "Aguilar", null, null, null, null, null, "PIGEON"))
                .containsExactly(new Problem("preferredChannel", "invalid-format"));
        assertThat(check("P1", "Rosa", "Aguilar", null, null, null, null, null, "SM\tS"))
                .containsExactly(new Problem("preferredChannel", "invalid-format"));
    }

    @Test
    void theStoredFormsAreStrippedAndABlankOptionalIsAbsent() {
        assertThat(GuardianFieldCheck.externalRef("  P1 ")).isEqualTo("P1");
        assertThat(GuardianFieldCheck.optionalOf("  x ")).isEqualTo("x");
        assertThat(GuardianFieldCheck.optionalOf("  ")).isNull();
        assertThat(GuardianFieldCheck.optionalOf(null)).isNull();
        assertThatThrownBy(() -> GuardianFieldCheck.externalRef(" ")).isInstanceOf(InvalidGuardianException.class);
    }
}
