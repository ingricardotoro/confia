package com.confia.students.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.students.domain.StudentFieldCheck.Problem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The field-by-field judgement of a row of the bulk import: every reason of the cut, each field on its own, and the
 * agreement with the rules the domain objects apply (so an import cannot accept what a hand registration refuses).
 */
class StudentFieldCheckTest {

    /** 2026-03-01 in Honduras (UTC-6) is still 2026-02-28 at 02:00 UTC. */
    private static final Instant NOW = Instant.parse("2026-03-01T15:00:00Z");

    private static List<Problem> check(String ref, String first, String last, String birth, String sex, String id) {
        return StudentFieldCheck.problems(ref, first, last, birth, sex, id, NOW);
    }

    private static List<Problem> valid(String birth) {
        return check("L1", "Ana", "Perez", birth, null, null);
    }

    @Test
    void aCompleteRowHasNoProblems() {
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", "F", "0801-2012-01234")).isEmpty();
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", "", "")).as("sex and document are optional").isEmpty();
        assertThat(check(" L1 ", " Ana ", " Perez ", " 2012-05-20 ", " M ", " 0801201201234 "))
                .as("cells are stripped").isEmpty();
    }

    @Test
    void everyRequiredFieldReportsRequiredWhenBlankOrMissing() {
        assertThat(check(null, " ", "", null, null, null)).containsExactly(new Problem("legacyId", "required"),
                new Problem("firstName", "required"), new Problem("lastName", "required"),
                new Problem("birthDate", "required"));
    }

    @Test
    void theLengthsAreTheOnesOfTheRegistryAfterStripping() {
        assertThat(check("L".repeat(64), "A".repeat(100), "B".repeat(100), "2012-05-20", null, null)).isEmpty();
        assertThat(check("L".repeat(65), "A".repeat(101), " " + "B".repeat(101) + " ", "2012-05-20", null, null))
                .containsExactly(new Problem("legacyId", "too-long"), new Problem("firstName", "too-long"),
                        new Problem("lastName", "too-long"));
        assertThat(check("L1", " " + "A".repeat(100) + " ", "Perez", "2012-05-20", null, null)).isEmpty();
    }

    @Test
    void aControlCharacterInATextIsInvalidFormat() {
        assertThat(check("L\t1", "An\na", "Pe\rrez", "2012-05-20", null, null)).containsExactly(
                new Problem("legacyId", "invalid-format"), new Problem("firstName", "invalid-format"),
                new Problem("lastName", "invalid-format"));
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", "F\n", null))
                .containsExactly(new Problem("sex", "invalid-format"));
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", null, "0801\n2012"))
                .containsExactly(new Problem("nationalId", "invalid-format"));
    }

    @Test
    void aTextThatLooksLikeAFormulaIsJustText() {
        assertThat(check("=1+1", "=HYPERLINK(\"x\")", "@SUM(A1)", "2012-05-20", null, null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"20120520", "2012-5-20", "20/05/2012", "2012-05-20T00:00", "12-05-20", "abcd-ef-gh",
            "2012-05-200"})
    void aBirthDateThatIsNotYyyyMmDdIsInvalidFormat(String birth) {
        assertThat(valid(birth)).containsExactly(new Problem("birthDate", "invalid-format"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2013-02-29", "2012-13-01", "2012-00-10", "2012-04-31", "2012-05-00"})
    void aBirthDateThatDoesNotExistIsInvalidDate(String birth) {
        assertThat(valid(birth)).containsExactly(new Problem("birthDate", "invalid-date"));
    }

    @Test
    void aLeapDayIsAcceptedOnlyInALeapYear() {
        assertThat(valid("2012-02-29")).isEmpty();
        assertThat(valid("2013-02-29")).containsExactly(new Problem("birthDate", "invalid-date"));
    }

    @Test
    void theBirthDateWindowIsTheOneOfTheRegistryAndTodayIsTheDayInHonduras() {
        assertThat(valid("1990-01-01")).isEmpty();
        assertThat(valid("1989-12-31")).containsExactly(new Problem("birthDate", "invalid-date"));
        assertThat(valid("2026-03-01")).as("today in Honduras").isEmpty();
        assertThat(valid("2026-03-02")).containsExactly(new Problem("birthDate", "future-birth-date"));
        assertThat(StudentFieldCheck.problems("L1", "Ana", "Perez", "2026-03-01", null, null,
                Instant.parse("2026-03-01T03:00:00Z")))
                .as("03:00 UTC is still the evening before in Honduras, so the 1st is tomorrow")
                .containsExactly(new Problem("birthDate", "future-birth-date"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"f", "X", "FEMALE", "MALE", "UNKNOWN"})
    void aSexOutsideTheClosedSetIsInvalidFormat(String sex) {
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", sex, null))
                .containsExactly(new Problem("sex", "invalid-format"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"F", "M", "UNSPECIFIED"})
    void everySexOfTheRegistryIsAccepted(String sex) {
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", sex, null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"1234567, invalid-format", "12345678901234567890123, too-long", "0801 2012 01234, invalid-format",
            "0801_2012_01234, invalid-format", "----------, invalid-format"})
    void aMalformedDocumentIsInvalidFormatOrTooLong(String id, String reason) {
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", null, id))
                .containsExactly(new Problem("nationalId", reason));
    }

    @Test
    void theDocumentFormatsOfTheRegistryAreAccepted() {
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", null, "12345678")).isEmpty();
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", null, "1".repeat(20))).isEmpty();
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", null, "ab-cd-1234")).isEmpty();
    }

    @Test
    void severalFieldsReportEachOnItsOwnAndNoProblemCarriesAValue() {
        List<Problem> problems = check("L1", "", "Perez", "2099-01-01", "Z", "ZZ");

        assertThat(problems).containsExactly(new Problem("firstName", "required"),
                new Problem("birthDate", "future-birth-date"), new Problem("sex", "invalid-format"),
                new Problem("nationalId", "invalid-format"));
        assertThat(problems.toString()).doesNotContain("Perez").doesNotContain("2099").doesNotContain("ZZ");
    }

    @Test
    void whatPassesTheCheckIsAcceptedByTheDomainAndTheValuesAreReadBack() {
        assertThat(check("L1", "Ana", "Perez", "2012-05-20", " M ", " 0801-2012-01234 ")).isEmpty();

        assertThat(StudentFieldCheck.externalRef(" L1 ")).isEqualTo("L1");
        assertThat(StudentFieldCheck.birthDateOf(" 2012-05-20 ")).hasToString("2012-05-20");
        assertThat(StudentFieldCheck.sexOf(" M ")).isEqualTo("M");
        assertThat(StudentFieldCheck.sexOf("  ")).isEqualTo("UNSPECIFIED");
        assertThat(StudentFieldCheck.sexOf(null)).isEqualTo("UNSPECIFIED");
        assertThat(StudentFieldCheck.nationalIdOf(" 0801-2012-01234 ")).isEqualTo("0801-2012-01234");
        assertThat(StudentFieldCheck.nationalIdOf("")).isNull();
        Student student = Student.create(java.util.UUID.randomUUID(), "E-000001", "Ana", "Perez",
                StudentFieldCheck.birthDateOf("2012-05-20"), StudentFieldCheck.sexOf(null),
                NationalId.of("0801-2012-01234"), NOW);
        assertThat(student.sex()).isEqualTo(Sex.UNSPECIFIED);
    }
}
