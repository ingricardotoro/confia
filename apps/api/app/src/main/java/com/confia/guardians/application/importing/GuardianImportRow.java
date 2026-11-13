package com.confia.guardians.application.importing;

/**
 * One row of a bulk import of guardians as the raw text of its cells: nothing is parsed or trimmed by the caller.
 * {@code line} is the line of the file where the row starts, used only to say which row a problem is about. Every
 * cell is personal data, so the text form shows only the line.
 */
public record GuardianImportRow(int line, String legacyId, String firstName, String lastName, String nationalId,
        String rtn, String email, String phone, String address, String preferredChannel) {

    @Override
    public String toString() {
        return "GuardianImportRow[line=" + line + "]";
    }
}
