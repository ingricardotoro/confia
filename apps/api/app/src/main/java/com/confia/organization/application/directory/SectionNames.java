package com.confia.organization.application.directory;

import java.util.Locale;
import java.util.Objects;

/**
 * The four names that identify a section in the files of another system, which has no identifier of ours: academic
 * year, modality, grade and section. Each one is compared without case, border spaces or repeated spaces, so the
 * record holds the names already normalized and two spellings of one name are equal.
 */
public record SectionNames(String academicYear, String modality, String grade, String section) {

    public SectionNames {
        academicYear = normalized(academicYear);
        modality = normalized(modality);
        grade = normalized(grade);
        section = normalized(section);
    }

    private static String normalized(String name) {
        return Objects.requireNonNull(name, "name").strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
