package com.confia.students.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Judges the rows of an enrollment file without writing anything (docs/cortes/imports-enrollments.md). The facts that
 * need the database (who the students are, which sections the names resolve to, what each student already holds) are
 * handed in by the use case, which reads them; the rules themselves live here. The rules the real enrollment applies
 * when it writes are the same ones ({@link Enrollment#enroll}): the judgment only anticipates them, and the capacity it
 * reports is an estimate that the write repeats under the lock of the section.
 *
 * <p>A problem is a line, the name of the column and a stable reason; it never carries a value of the file.
 */
public final class EnrollmentImportRules {

    public static final String REQUIRED = "required";
    public static final String INVALID_FORMAT = "invalid-format";
    public static final String TOO_LONG = "too-long";
    public static final String INVALID_DATE = "invalid-date";
    public static final String UNKNOWN_STUDENT = "unknown-student";
    public static final String UNKNOWN_SECTION = "unknown-section";
    public static final String AMBIGUOUS_SECTION = "ambiguous-section";
    public static final String DUPLICATE_IN_FILE = "duplicate-in-file";
    public static final String STUDENT_NOT_ACTIVE = "student-not-active";
    public static final String INACTIVE_REFERENCE = "inactive-reference";
    public static final String ACADEMIC_YEAR_CLOSED = "academic-year-closed";
    public static final String DATE_OUT_OF_RANGE = "enrollment-date-out-of-range";
    public static final String ALREADY_IMPORTED = "already-imported";
    public static final String DUPLICATE_ACTIVE_ENROLLMENT = "duplicate-active-enrollment";
    public static final String SECTION_FULL = "section-full";

    private static final int MAX_NAME_LENGTH = 100;
    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private EnrollmentImportRules() {
    }

    /** A line of the file, every cell as it was read. */
    public record Row(int line, String studentLegacyId, String academicYear, String modality, String grade,
            String section, String enrolledOn) {
    }

    /** What the registry says about a reference of the source system. */
    public record StudentFacts(UUID studentId, boolean active) {
    }

    /** A section the names of a row resolve to, with the seats it holds now. */
    public record SectionFacts(UUID sectionId, UUID academicYearId, boolean active, boolean yearClosed,
            LocalDate yearStart, LocalDate yearEnd, Integer capacity, int activeEnrollments) {

        boolean hasRoom(int taken) {
            return capacity == null || taken < capacity;
        }

        boolean contains(LocalDate day) {
            return !day.isBefore(yearStart) && !day.isAfter(yearEnd);
        }
    }

    /** A field of a row that cannot be imported, or a row that is skipped, and why. */
    public record Problem(int line, String field, String reason) {
    }

    /** The key under which the sections are looked up: the four names, without case, border or repeated spaces. */
    public static String sectionKey(Row row) {
        return String.join("|", normalized(row.academicYear()), normalized(row.modality()), normalized(row.grade()),
                normalized(row.section()));
    }

    /** The key of the active enrollment a student holds in an academic year. */
    public static String studentYearKey(UUID studentId, UUID academicYearId) {
        return studentId + ":" + academicYearId;
    }

    /** The reference of the student as the registry stores it: without border spaces. */
    public static String studentReference(Row row) {
        return row.studentLegacyId() == null ? "" : row.studentLegacyId().strip();
    }

    /**
     * Judges every row. Problems come in line order.
     *
     * @param students the registry's answer for the references of the file, by reference
     * @param sectionsByKey the sections each {@link #sectionKey} resolves to (none, one, or several when the names are
     *     not unique)
     * @param activeSectionByStudentYear the section of the active enrollment each {@link #studentYearKey} holds
     * @param today the day it is in Honduras
     */
    public static List<Problem> judge(List<Row> rows, Map<String, StudentFacts> students,
            Map<String, List<SectionFacts>> sectionsByKey, Map<String, UUID> activeSectionByStudentYear,
            LocalDate today) {
        List<Problem> problems = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();
        Map<UUID, Integer> takenInFile = new HashMap<>();
        for (Row row : rows) {
            Problem found = judgeRow(row, students, sectionsByKey, activeSectionByStudentYear, today, seenInFile,
                    takenInFile, problems);
            if (found != null) {
                problems.add(found);
            }
        }
        problems.sort(Comparator.comparingInt(Problem::line));
        return List.copyOf(problems);
    }

    private static Problem judgeRow(Row row, Map<String, StudentFacts> students,
            Map<String, List<SectionFacts>> sectionsByKey, Map<String, UUID> activeSectionByStudentYear,
            LocalDate today, Set<String> seenInFile, Map<UUID, Integer> takenInFile, List<Problem> problems) {
        int before = problems.size();
        text(row.line(), "studentLegacyId", row.studentLegacyId(), 64, problems);
        text(row.line(), "academicYear", row.academicYear(), MAX_NAME_LENGTH, problems);
        text(row.line(), "modality", row.modality(), MAX_NAME_LENGTH, problems);
        text(row.line(), "grade", row.grade(), MAX_NAME_LENGTH, problems);
        text(row.line(), "section", row.section(), MAX_NAME_LENGTH, problems);
        LocalDate enrolledOn = date(row.line(), row.enrolledOn(), problems);
        if (problems.size() > before) {
            return null;
        }

        StudentFacts student = students.get(studentReference(row));
        if (student == null) {
            return new Problem(row.line(), "studentLegacyId", UNKNOWN_STUDENT);
        }
        List<SectionFacts> candidates = sectionsByKey.getOrDefault(sectionKey(row), List.of());
        if (candidates.isEmpty()) {
            return new Problem(row.line(), "section", UNKNOWN_SECTION);
        }
        if (candidates.size() > 1) {
            return new Problem(row.line(), "section", AMBIGUOUS_SECTION);
        }
        SectionFacts section = candidates.get(0);

        String studentYear = studentYearKey(student.studentId(), section.academicYearId());
        if (!seenInFile.add(studentYear)) {
            return new Problem(row.line(), "studentLegacyId", DUPLICATE_IN_FILE);
        }
        if (!student.active()) {
            return new Problem(row.line(), "studentLegacyId", STUDENT_NOT_ACTIVE);
        }
        if (section.yearClosed()) {
            return new Problem(row.line(), "academicYear", ACADEMIC_YEAR_CLOSED);
        }
        if (!section.active()) {
            return new Problem(row.line(), "section", INACTIVE_REFERENCE);
        }
        if (!section.contains(enrolledOn) || enrolledOn.isAfter(today)) {
            return new Problem(row.line(), "enrolledOn", DATE_OUT_OF_RANGE);
        }
        UUID held = activeSectionByStudentYear.get(studentYear);
        if (held != null) {
            return new Problem(row.line(), "studentLegacyId",
                    held.equals(section.sectionId()) ? ALREADY_IMPORTED : DUPLICATE_ACTIVE_ENROLLMENT);
        }
        int taken = section.activeEnrollments() + takenInFile.getOrDefault(section.sectionId(), 0);
        if (!section.hasRoom(taken)) {
            return new Problem(row.line(), "section", SECTION_FULL);
        }
        takenInFile.merge(section.sectionId(), 1, Integer::sum);
        return null;
    }

    /** The day of a cell that {@link #judge} found without problems. */
    public static LocalDate dateOf(String raw) {
        return LocalDate.parse(raw.strip(), DATE);
    }

    private static void text(int line, String field, String value, int maxLength, List<Problem> problems) {
        if (value == null || value.isBlank()) {
            problems.add(new Problem(line, field, REQUIRED));
        } else if (value.chars().anyMatch(Character::isISOControl)) {
            problems.add(new Problem(line, field, INVALID_FORMAT));
        } else if (value.strip().length() > maxLength) {
            problems.add(new Problem(line, field, TOO_LONG));
        }
    }

    private static LocalDate date(int line, String raw, List<Problem> problems) {
        if (raw == null || raw.isBlank()) {
            problems.add(new Problem(line, "enrolledOn", REQUIRED));
            return null;
        }
        String text = raw.strip();
        if (text.chars().anyMatch(Character::isISOControl) || !DATE_SHAPE.matcher(text).matches()) {
            problems.add(new Problem(line, "enrolledOn", INVALID_FORMAT));
            return null;
        }
        try {
            return LocalDate.parse(text, DATE);
        } catch (DateTimeParseException e) {
            problems.add(new Problem(line, "enrolledOn", INVALID_DATE));
            return null;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
