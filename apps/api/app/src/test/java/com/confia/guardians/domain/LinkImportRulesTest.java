package com.confia.guardians.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.guardians.domain.LinkImportRules.GuardianFacts;
import com.confia.guardians.domain.LinkImportRules.Problem;
import com.confia.guardians.domain.LinkImportRules.Row;
import com.confia.guardians.domain.LinkImportRules.StudentFacts;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The rules of the rows of a links import (docs/cortes/imports-guardians.md): the rows are grouped by student and each
 * group is judged as the whole set with the rule of the registry (INV-32). Money-like arithmetic is exact: 99.99 and
 * 100.01 are refused and 33.33 + 33.33 + 33.34 is accepted. A group is rejected or accepted whole.
 */
class LinkImportRulesTest {

    private final Map<String, StudentFacts> students = new HashMap<>();
    private final Map<String, GuardianFacts> guardians = new HashMap<>();

    LinkImportRulesTest() {
        students.put("S1", new StudentFacts(UUID.randomUUID(), true, false));
        students.put("S2", new StudentFacts(UUID.randomUUID(), true, false));
        for (String reference : List.of("G1", "G2", "G3")) {
            guardians.put(reference, new GuardianFacts(UUID.randomUUID(), true));
        }
    }

    private static Row row(int line, String student, String guardian, String percent, String primary,
            String billing) {
        return new Row(line, student, guardian, "MOTHER", percent, primary, billing);
    }

    private List<Problem> judge(Row... rows) {
        return LinkImportRules.judge(List.of(rows), students, guardians);
    }

    private static Problem problem(int line, String field, String reason) {
        return new Problem(line, field, reason);
    }

    // --- INV-32, exact ---

    @Test
    void aGroupThatAddsUpToExactlyOneHundredIsAcceptedEvenWithThirdsInCents() {
        assertThat(judge(row(2, "S1", "G1", "33.33", "true", ""), row(3, "S1", "G2", "33.33", "", "true"),
                row(4, "S1", "G3", "33.34", "false", "false"))).isEmpty();
    }

    @Test
    void aGroupOfOneGuardianWithOneHundredIsAccepted() {
        assertThat(judge(row(2, "S1", "G1", "100", null, null))).isEmpty();
    }

    @Test
    void ninetyNinePointNinetyNineIsRefusedOnEveryLineOfTheGroup() {
        assertThat(judge(row(2, "S1", "G1", "50.00", "", ""), row(3, "S1", "G2", "49.99", "", "")))
                .containsExactly(problem(2, "financialResponsibilityPercent", "sum-not-100"),
                        problem(3, "financialResponsibilityPercent", "sum-not-100"));
    }

    @Test
    void oneHundredPointZeroOneIsRefusedOnEveryLineOfTheGroup() {
        assertThat(judge(row(2, "S1", "G1", "50.00", "", ""), row(3, "S1", "G2", "50.01", "", "")))
                .extracting(Problem::reason).containsExactly("sum-not-100", "sum-not-100");
    }

    @Test
    void twoPrimaryPayersRejectTheWholeGroup() {
        assertThat(judge(row(2, "S1", "G1", "50", "true", ""), row(3, "S1", "G2", "50", "TRUE", "")))
                .containsExactly(problem(2, "isPrimaryPayer", "multiple-primary-payer"),
                        problem(3, "isPrimaryPayer", "multiple-primary-payer"));
    }

    @Test
    void twoBillingRecipientsRejectTheWholeGroup() {
        assertThat(judge(row(2, "S1", "G1", "50", "", "true"), row(3, "S1", "G2", "50", "", "true")))
                .containsExactly(problem(2, "isBillingRecipient", "multiple-billing-recipient"),
                        problem(3, "isBillingRecipient", "multiple-billing-recipient"));
    }

    @Test
    void aGuardianRepeatedInTheGroupRejectsTheWholeGroupEvenWithTheSpellingDifferent() {
        assertThat(judge(row(2, "S1", "G1", "50", "", ""), row(3, " S1 ", " G1", "50", "", "")))
                .containsExactly(problem(2, "guardianLegacyId", "duplicate-guardian"),
                        problem(3, "guardianLegacyId", "duplicate-guardian"));
    }

    @Test
    void aDefectiveGroupDoesNotStopTheValidOnes() {
        assertThat(judge(row(2, "S1", "G1", "90", "", ""), row(3, "S2", "G1", "100", "", ""))).containsExactly(
                problem(2, "financialResponsibilityPercent", "sum-not-100"));
    }

    // --- cells ---

    @Test
    void aBadPercentRejectsItsLineAndTheOthersOfTheGroupAreRejectedWithTheGroup() {
        assertThat(judge(row(2, "S1", "G1", "0", "", ""), row(3, "S1", "G2", "100", "", "")))
                .containsExactly(problem(2, "financialResponsibilityPercent", "invalid-percent"),
                        problem(3, "row", "group-rejected"));
    }

    @Test
    void everyShapeOfBadPercentIsInvalidPercentAndBlankIsRequired() {
        for (String bad : List.of("100.01", "33.333", "abc", "-5", "1e2", "1,5", "1000", "")) {
            Problem found = judge(row(2, "S1", "G1", bad, "", "")).getFirst();
            assertThat(found.field()).isEqualTo("financialResponsibilityPercent");
            assertThat(found.reason()).isEqualTo(bad.isEmpty() ? "required" : "invalid-percent");
        }
        assertThat(judge(row(2, "S1", "G1", null, "", "")).getFirst().reason()).isEqualTo("required");
    }

    @Test
    void theReferencesAreRequiredAndShapedLikeTheOnesOfTheOtherImports() {
        assertThat(judge(new Row(2, "", "G1", "MOTHER", "100", "", ""))).containsExactly(
                problem(2, "studentLegacyId", "required"));
        assertThat(judge(new Row(2, "S1", null, "MOTHER", "100", "", ""))).containsExactly(
                problem(2, "guardianLegacyId", "required"));
        assertThat(judge(new Row(2, "S\t1", "G1", "MOTHER", "100", "", ""))).containsExactly(
                problem(2, "studentLegacyId", "invalid-format"));
        assertThat(judge(new Row(2, "S1", "G".repeat(65), "MOTHER", "100", "", ""))).containsExactly(
                problem(2, "guardianLegacyId", "too-long"));
    }

    @Test
    void theRelationshipMustBeOneOfTheRegistry() {
        assertThat(judge(new Row(2, "S1", "G1", "", "100", "", ""))).containsExactly(
                problem(2, "relationship", "required"));
        assertThat(judge(new Row(2, "S1", "G1", "UNCLE", "100", "", ""))).containsExactly(
                problem(2, "relationship", "invalid-format"));
        assertThat(judge(new Row(2, "S1", "G1", " LEGAL_GUARDIAN ", "100", "", ""))).isEmpty();
    }

    @Test
    void aFlagIsTrueOrFalseInAnyCaseOrBlank() {
        assertThat(judge(row(2, "S1", "G1", "100", "yes", "1"))).containsExactly(
                problem(2, "isPrimaryPayer", "invalid-format"), problem(2, "isBillingRecipient", "invalid-format"));
        assertThat(judge(row(2, "S1", "G1", "100", "False", " TRUE "))).isEmpty();
    }

    // --- the registry ---

    @Test
    void anUnknownReferenceRejectsItsLineAndTheGroup() {
        assertThat(judge(row(2, "S1", "G1", "50", "", ""), row(3, "S1", "NOPE", "50", "", "")))
                .containsExactly(problem(2, "row", "group-rejected"), problem(3, "guardianLegacyId",
                        "unknown-guardian"));
        assertThat(judge(row(2, "NOPE", "G1", "100", "", ""))).containsExactly(
                problem(2, "studentLegacyId", "unknown-student"));
    }

    @Test
    void anInactiveStudentRejectsTheWholeGroup() {
        students.put("S1", new StudentFacts(UUID.randomUUID(), false, false));
        assertThat(judge(row(2, "S1", "G1", "50", "", ""), row(3, "S1", "G2", "50", "", ""))).containsExactly(
                problem(2, "studentLegacyId", "student-not-active"),
                problem(3, "studentLegacyId", "student-not-active"));
    }

    @Test
    void anInactiveGuardianIsNamedAndTheRestOfTheGroupIsRejectedWithIt() {
        guardians.put("G2", new GuardianFacts(UUID.randomUUID(), false));
        assertThat(judge(row(2, "S1", "G1", "50", "", ""), row(3, "S1", "G2", "50", "", ""))).containsExactly(
                problem(2, "row", "group-rejected"), problem(3, "guardianLegacyId", "guardian-not-active"));
    }

    @Test
    void aStudentThatAlreadyHasLinksIsSkippedWholeAndNeverReplaced() {
        students.put("S1", new StudentFacts(UUID.randomUUID(), true, true));
        assertThat(judge(row(2, "S1", "G1", "50", "", ""), row(3, "S1", "G2", "50", "", ""))).containsExactly(
                problem(2, "studentLegacyId", "student-already-linked"),
                problem(3, "studentLegacyId", "student-already-linked"));
    }

    @Test
    void aDefectOfTheSetIsReportedBeforeTheStudentAlreadyBeingLinked() {
        students.put("S1", new StudentFacts(UUID.randomUUID(), true, true));
        assertThat(judge(row(2, "S1", "G1", "90", "", ""))).extracting(Problem::reason).containsExactly("sum-not-100");
    }

    @Test
    void theProblemsComeInLineOrderAcrossGroups() {
        assertThat(judge(row(2, "S1", "G1", "50", "", ""), row(3, "S2", "G1", "10", "", ""),
                row(4, "S1", "G2", "40", "", ""))).extracting(Problem::line).containsExactly(2, 3, 4);
    }

    // --- helpers used by the gateway ---

    @Test
    void theReferencesToLookUpAreTheUsableOnesStrippedAndWithoutRepeats() {
        List<Row> rows = List.of(row(2, " S1 ", "G1", "50", "", ""), row(3, "S1", "G\t2", "50", "", ""),
                row(4, "", "", "", "", ""));
        assertThat(LinkImportRules.studentReferences(rows)).containsExactly("S1");
        assertThat(LinkImportRules.guardianReferences(rows)).containsExactly("G1");
    }

    @Test
    void theGroupsKeepTheOrderOfTheFileAndTheValuesAreReadExactly() {
        Row first = new Row(2, "S2", "G1", " FATHER ", "33.30", "TRUE", null);
        Row second = row(3, "S1", "G2", "66.70", "", "True");
        Map<String, List<Row>> groups = LinkImportRules.groupsOf(List.of(first, second));

        assertThat(groups.keySet()).containsExactly("S2", "S1");
        assertThat(LinkImportRules.percentOf(first)).isEqualByComparingTo(new BigDecimal("33.30"));
        assertThat(LinkImportRules.relationshipOf(first)).isEqualTo("FATHER");
        assertThat(LinkImportRules.primaryPayerOf(first)).isTrue();
        assertThat(LinkImportRules.billingRecipientOf(first)).isFalse();
        assertThat(LinkImportRules.primaryPayerOf(second)).isFalse();
        assertThat(LinkImportRules.billingRecipientOf(second)).isTrue();
    }

    @Test
    void aRowShowsOnlyItsLine() {
        assertThat(row(7, "S1", "G1", "100", "", "").toString()).isEqualTo("Row[line=7]");
    }
}
