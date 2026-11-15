package com.confia.guardians.application.importing;

/**
 * One row of a bulk import of links as the raw text of its cells: the references of the student and of the guardian in
 * the source system, the relationship, the percentage and the two flags. {@code line} is where the row starts. A
 * flag may be {@code null} (the column is absent) or blank, which is {@code false}. The text form shows only the line.
 */
public record LinkImportRow(int line, String studentLegacyId, String guardianLegacyId, String relationship,
        String financialResponsibilityPercent, String isPrimaryPayer, String isBillingRecipient) {

    @Override
    public String toString() {
        return "LinkImportRow[line=" + line + "]";
    }
}
