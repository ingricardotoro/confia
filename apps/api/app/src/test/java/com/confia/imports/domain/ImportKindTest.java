package com.confia.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The kinds of import, their names in the routes and the template of the file of each. */
class ImportKindTest {

    @Test
    void everyKindHasItsNameInTheRoutesAndItIsFoundByIt() {
        assertThat(ImportKind.values()).extracting(ImportKind::route)
                .containsExactly("students", "guardians", "guardian-links", "enrollments");
        for (ImportKind kind : ImportKind.values()) {
            assertThat(ImportKind.ofRoute(kind.route())).isSameAs(kind);
        }
        assertThat(ImportKind.ofRoute("STUDENTS")).isNull();
        assertThat(ImportKind.ofRoute(null)).isNull();
    }

    @Test
    void theHeaderOfEachTemplateListsTheRequiredColumnsAndThenTheOptionalOnes() {
        assertThat(ImportKind.STUDENTS.template().header()).isEqualTo(StudentsCsv.TEMPLATE_HEADER);
        assertThat(ImportKind.GUARDIANS.template().header())
                .isEqualTo("legacyId,firstName,lastName,nationalId,rtn,email,phone,address,preferredChannel");
        assertThat(ImportKind.GUARDIAN_LINKS.template().header()).isEqualTo(
                "studentLegacyId,guardianLegacyId,relationship,financialResponsibilityPercent,isPrimaryPayer,"
                        + "isBillingRecipient");
        assertThat(ImportKind.ENROLLMENTS.template().header())
                .isEqualTo("studentLegacyId,academicYear,modality,grade,section,enrolledOn");
    }

    @Test
    void aGuardiansFileIsReadByColumnNameWithTheOptionalOnesAbsentAsNull() {
        CsvTemplate.Parsed parsed = ImportKind.GUARDIANS.template()
                .parse("lastName;legacyId;firstName;email\nAguilar;P1;Rosa;rosa@example.org\n", 10);

        CsvTemplate.Row row = parsed.rows().getFirst();
        assertThat(row.line()).isEqualTo(2);
        assertThat(row.cell("legacyId")).isEqualTo("P1");
        assertThat(row.cell("email")).isEqualTo("rosa@example.org");
        assertThat(row.cell("rtn")).isNull();
        assertThat(row.toString()).isEqualTo("Row[line=2]").doesNotContain("Rosa");
    }

    @Test
    void aLinksFileNeedsItsFourColumnsAndNamesTheMissingOnesByTheirTemplateName() {
        assertThatThrownBy(() -> ImportKind.GUARDIAN_LINKS.template().parse("studentLegacyId,relationship\nS1,MOTHER\n", 10))
                .isInstanceOfSatisfying(InvalidImportFileException.class, e -> assertThat(e.problems()).containsExactly(
                        new InvalidImportFileException.Problem("guardianLegacyId", "missing-column"),
                        new InvalidImportFileException.Problem("financialResponsibilityPercent", "missing-column")));
    }

    @Test
    void aStudentsHeaderIsAnUnknownTemplateForGuardiansAndIsReportedByPosition() {
        assertThatThrownBy(() -> ImportKind.GUARDIANS.template().parse(StudentsCsv.TEMPLATE_HEADER + "\n", 10))
                .isInstanceOfSatisfying(InvalidImportFileException.class, e -> assertThat(e.problems())
                        .contains(new InvalidImportFileException.Problem("columns[3]", "unknown-column"),
                                new InvalidImportFileException.Problem("columns[5]", "unknown-column")));
    }

    @Test
    void aSkippedReasonIsOneOfTheTwoThatMeanItIsAlreadyThere() {
        assertThat(List.of(new Rejection(2, "legacyId", "already-imported"),
                new Rejection(2, "studentLegacyId", "student-already-linked")))
                .allMatch(Rejection::isSkipped);
        assertThat(new Rejection(2, "legacyId", "required").isSkipped()).isFalse();
    }
}
