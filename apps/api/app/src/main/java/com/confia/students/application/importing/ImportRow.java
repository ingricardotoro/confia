package com.confia.students.application.importing;

/**
 * One row of a bulk import as the raw text of its cells: nothing is parsed or trimmed by the caller. {@code line} is
 * the line of the file where the row starts, used only to say which row a problem is about. Every cell is personal
 * data of a minor, so the text form shows only the line.
 */
public record ImportRow(int line, String legacyId, String firstName, String lastName, String birthDate, String sex,
        String nationalId) {

    @Override
    public String toString() {
        return "ImportRow[line=" + line + "]";
    }
}
