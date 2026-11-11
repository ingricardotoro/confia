package com.confia.students.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NationalIdTest {

    @Test
    void theNormalFormIsUppercaseWithoutHyphens() {
        assertThat(NationalId.of("0801-1999-12345").normalized()).isEqualTo("0801199912345");
        assertThat(NationalId.of("ab-cd-1234").normalized()).isEqualTo("ABCD1234");
    }

    @Test
    void differentFormatsOfTheSameDocumentHaveTheSameNormalForm() {
        assertThat(NationalId.of("0801-1999-12345").normalized())
                .isEqualTo(NationalId.of("0801199912345").normalized())
                .isEqualTo(NationalId.of(" 08011999-12345 ").normalized());
        assertThat(NationalId.of("abc12345").normalized()).isEqualTo(NationalId.of("ABC-12345").normalized());
    }

    @Test
    void theLastFourCharactersOfTheNormalFormAreTheOnlyPartThatIsExposed() {
        NationalId id = NationalId.of("0801-1999-12345");

        assertThat(id.last4()).isEqualTo("2345");
        assertThat(id.masked()).isEqualTo("****2345");
    }

    @Test
    void theBorderLengthsAreEightAndTwentyCharacters() {
        assertThat(NationalId.of("12345678").normalized()).isEqualTo("12345678");
        assertThat(NationalId.of("1".repeat(20)).normalized()).hasSize(20);
        assertThatThrownBy(() -> NationalId.of("1234567")).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> NationalId.of("1".repeat(21))).isInstanceOf(InvalidStudentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234 5678", "1234_5678", "1234.5678", "12345678!", "1234567ñ",
            "١٢٣٤٥٦٧٨"})
    void onlyLettersDigitsAndHyphensAreAccepted(String raw) {
        assertThatThrownBy(() -> NationalId.of(raw)).isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void aDocumentMadeOfHyphensOrWithTooFewCharactersOnceNormalisedIsRefused() {
        assertThatThrownBy(() -> NationalId.of("--------")).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> NationalId.of("12-34---")).isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void nullAndBlankAreRefused() {
        assertThatThrownBy(() -> NationalId.of(null)).isInstanceOf(InvalidStudentException.class);
        assertThatThrownBy(() -> NationalId.of("   ")).isInstanceOf(InvalidStudentException.class);
    }

    @Test
    void textRepresentationNeverShowsTheDocument() {
        NationalId id = NationalId.of("0801-1999-12345");

        assertThat(id.toString()).doesNotContain("0801").doesNotContain("12345").doesNotContain("2345");
    }

    @Test
    void theRefusalNeverRepeatsTheRejectedText() {
        assertThatThrownBy(() -> NationalId.of("secret!!")).hasMessageNotContaining("secret");
    }

    // --- The change requested by an edit ---

    @Test
    void aMissingValueKeepsABlankOneRemovesAndAnythingElseReplaces() {
        assertThat(NationalIdChange.of(null)).isSameAs(NationalIdChange.Keep.INSTANCE);
        assertThat(NationalIdChange.of("")).isSameAs(NationalIdChange.Remove.INSTANCE);
        assertThat(NationalIdChange.of("   ")).isSameAs(NationalIdChange.Remove.INSTANCE);
        assertThat(NationalIdChange.of("0801-1999-12345"))
                .isInstanceOfSatisfying(NationalIdChange.Replace.class,
                        replace -> assertThat(replace.nationalId().last4()).isEqualTo("2345"));
        assertThatThrownBy(() -> NationalIdChange.of("short")).isInstanceOf(InvalidStudentException.class);
    }
}
