package com.confia.students.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.students.domain.EnrollmentImportRules.Problem;
import com.confia.students.domain.EnrollmentImportRules.Row;
import com.confia.students.domain.EnrollmentImportRules.SectionFacts;
import com.confia.students.domain.EnrollmentImportRules.StudentFacts;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnrollmentImportRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final UUID STUDENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_STUDENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID YEAR = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID SECTION_A = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID SECTION_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final Map<String, StudentFacts> students = new HashMap<>(Map.of(
            "S-1", new StudentFacts(STUDENT, true), "S-2", new StudentFacts(OTHER_STUDENT, true)));
    private final Map<String, List<SectionFacts>> sections = new HashMap<>();
    private final Map<String, UUID> activeSections = new HashMap<>();

    private static Row row(int line, String student, String section, String enrolledOn) {
        return new Row(line, student, "2026", "Regular", "Septimo", section, enrolledOn);
    }

    private static SectionFacts section(UUID id, boolean active, boolean closed, Integer capacity, int taken) {
        return new SectionFacts(id, YEAR, active, closed, LocalDate.of(2026, 1, 15), LocalDate.of(2026, 11, 30),
                capacity, taken);
    }

    private void sectionA(SectionFacts facts) {
        sections.put(EnrollmentImportRules.sectionKey(row(1, "S-1", "A", "2026-02-01")), List.of(facts));
    }

    private List<Problem> judge(Row... rows) {
        return EnrollmentImportRules.judge(List.of(rows), students, sections, activeSections, TODAY);
    }

    @Test
    void aValidRowHasNoProblem() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        assertThat(judge(row(2, "S-1", "A", "2026-02-01"))).isEmpty();
    }

    @Test
    void sectionNamesMatchIgnoringCaseAndSurroundingSpaces() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        Row sloppy = new Row(2, " S-1 ", " 2026 ", "REGULAR", "septimo", "  a ", "2026-02-01");
        assertThat(judge(sloppy)).isEmpty();
    }

    @Test
    void blankFieldsAreRequiredAndTheRowIsNotJudgedFurther() {
        Row blank = new Row(2, "", "", "", "", "", "");
        assertThat(judge(blank)).containsExactlyInAnyOrder(
                new Problem(2, "studentLegacyId", "required"), new Problem(2, "academicYear", "required"),
                new Problem(2, "modality", "required"), new Problem(2, "grade", "required"),
                new Problem(2, "section", "required"), new Problem(2, "enrolledOn", "required"));
    }

    @Test
    void aMalformedOrImpossibleDateIsRejectedWithItsOwnReason() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        assertThat(judge(row(2, "S-1", "A", "01/02/2026"))).containsExactly(new Problem(2, "enrolledOn", "invalid-format"));
        assertThat(judge(row(3, "S-1", "A", "2026-02-30"))).containsExactly(new Problem(3, "enrolledOn", "invalid-date"));
    }

    @Test
    void anUnknownStudentOrSectionIsRejected() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        assertThat(judge(row(2, "NOPE", "A", "2026-02-01"))).containsExactly(new Problem(2, "studentLegacyId", "unknown-student"));
        assertThat(judge(row(3, "S-1", "Z", "2026-02-01"))).containsExactly(new Problem(3, "section", "unknown-section"));
    }

    @Test
    void aNameThatMatchesTwoSectionsIsAmbiguous() {
        sections.put(EnrollmentImportRules.sectionKey(row(1, "S-1", "A", "2026-02-01")),
                List.of(section(SECTION_A, true, false, 30, 0), section(SECTION_B, true, false, 30, 0)));
        assertThat(judge(row(2, "S-1", "A", "2026-02-01"))).containsExactly(new Problem(2, "section", "ambiguous-section"));
    }

    @Test
    void anInactiveStudentSectionOrAClosedYearIsRejected() {
        students.put("S-3", new StudentFacts(UUID.randomUUID(), false));
        sectionA(section(SECTION_A, true, false, 30, 0));
        assertThat(judge(row(2, "S-3", "A", "2026-02-01"))).containsExactly(new Problem(2, "studentLegacyId", "student-not-active"));

        sectionA(section(SECTION_A, false, false, 30, 0));
        assertThat(judge(row(3, "S-1", "A", "2026-02-01"))).containsExactly(new Problem(3, "section", "inactive-reference"));

        sectionA(section(SECTION_A, true, true, 30, 0));
        assertThat(judge(row(4, "S-1", "A", "2026-02-01"))).containsExactly(new Problem(4, "academicYear", "academic-year-closed"));
    }

    @Test
    void theDateMustFallInsideTheYearAndNotBeInTheFuture() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        assertThat(judge(row(2, "S-1", "A", "2026-01-14"))).containsExactly(new Problem(2, "enrolledOn", "enrollment-date-out-of-range"));
        assertThat(judge(row(3, "S-1", "A", "2026-11-14"))).containsExactly(new Problem(3, "enrolledOn", "enrollment-date-out-of-range"));
        assertThat(judge(row(4, "S-1", "A", "2026-01-15"))).isEmpty();
        assertThat(judge(row(5, "S-1", "A", "2026-11-13"))).isEmpty();
    }

    @Test
    void theSameStudentTwiceInTheYearRejectsTheSecondLine() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        assertThat(judge(row(2, "S-1", "A", "2026-02-01"), row(3, "S-1", "A", "2026-02-02")))
                .containsExactly(new Problem(3, "studentLegacyId", "duplicate-in-file"));
    }

    @Test
    void anActiveEnrollmentInTheSameSectionIsSkippedAndInAnotherIsRejected() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        activeSections.put(EnrollmentImportRules.studentYearKey(STUDENT, YEAR), SECTION_A);
        assertThat(judge(row(2, "S-1", "A", "2026-02-01"))).containsExactly(new Problem(2, "studentLegacyId", "already-imported"));

        activeSections.put(EnrollmentImportRules.studentYearKey(STUDENT, YEAR), SECTION_B);
        assertThat(judge(row(3, "S-1", "A", "2026-02-01")))
                .containsExactly(new Problem(3, "studentLegacyId", "duplicate-active-enrollment"));
    }

    @Test
    void theCapacityCountsTheActiveEnrollmentsAndTheRowsBeforeInTheFile() {
        sectionA(section(SECTION_A, true, false, 2, 1));
        assertThat(judge(row(2, "S-1", "A", "2026-02-01"), row(3, "S-2", "A", "2026-02-01")))
                .containsExactly(new Problem(3, "section", "section-full"));
    }

    @Test
    void aSkippedOrRejectedRowTakesNoSeat() {
        sectionA(section(SECTION_A, true, false, 1, 0));
        activeSections.put(EnrollmentImportRules.studentYearKey(STUDENT, YEAR), SECTION_A);
        assertThat(judge(row(2, "S-1", "A", "2026-02-01"), row(3, "S-2", "A", "2026-02-01")))
                .containsExactly(new Problem(2, "studentLegacyId", "already-imported"));
    }

    @Test
    void aSectionWithoutLimitNeverFills() {
        sectionA(section(SECTION_A, true, false, null, 500));
        assertThat(judge(row(2, "S-1", "A", "2026-02-01"))).isEmpty();
    }

    @Test
    void problemsComeInLineOrder() {
        sectionA(section(SECTION_A, true, false, 30, 0));
        List<Problem> problems = judge(row(5, "NOPE", "A", "2026-02-01"), row(3, "S-1", "Z", "2026-02-01"));
        assertThat(problems).extracting(Problem::line).containsExactly(3, 5);
    }
}
