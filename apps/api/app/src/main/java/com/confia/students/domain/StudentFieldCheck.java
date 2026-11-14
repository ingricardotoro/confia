package com.confia.students.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Judges the raw text of a row of a bulk import with the rules of the registry and says, for each field that breaks
 * one, the stable reason (never the value). It exists because the domain objects refuse a bad value with one error
 * and no field, and an import has to tell the person which field of which line to fix. It does not restate a rule: the
 * name length comes from {@link Names}, the sex from {@link Sex}, the document from {@link NationalId}, and the birth
 * date window from {@link Student#EARLIEST_BIRTH_DATE} and the day in Honduras. Nothing here keeps or returns a value.
 *
 * <p>An import is stricter than the hand registration in one respect only: a control character (a tab or a line break
 * inside a quoted cell, for example) is refused as {@code invalid-format}, because such a cell is a spreadsheet
 * artefact and never a name.
 */
public final class StudentFieldCheck {

    public static final String REQUIRED = "required";
    public static final String INVALID_FORMAT = "invalid-format";
    public static final String TOO_LONG = "too-long";
    public static final String INVALID_DATE = "invalid-date";
    public static final String FUTURE_BIRTH_DATE = "future-birth-date";

    /** The longest reference a source system may give a student (the width of the column). */
    public static final int MAX_EXTERNAL_REF_LENGTH = 64;
    private static final int MAX_NATIONAL_ID_LENGTH = 20;

    private static final ZoneId HONDURAS = ZoneId.of("America/Tegucigalpa");
    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /** A field that breaks a rule and the stable reason; never the rejected value. */
    public record Problem(String field, String reason) {
    }

    private StudentFieldCheck() {
    }

    /**
     * The problems of a row. {@code sex} and {@code nationalId} may be blank (UNSPECIFIED and no document); the other
     * four fields are required.
     */
    public static List<Problem> problems(String externalRef, String firstName, String lastName, String birthDate,
            String sex, String nationalId, Instant now) {
        List<Problem> problems = new ArrayList<>();
        text("legacyId", externalRef, MAX_EXTERNAL_REF_LENGTH, problems);
        text("firstName", firstName, Names.MAX_NAME_LENGTH, problems);
        text("lastName", lastName, Names.MAX_NAME_LENGTH, problems);
        birthDate(birthDate, now, problems);
        if (!isBlank(sex) && (hasControl(sex) || !isSex(sex.strip()))) {
            problems.add(new Problem("sex", INVALID_FORMAT));
        }
        if (!isBlank(nationalId)) {
            nationalId(nationalId, problems);
        }
        return problems;
    }

    /** The reference as stored: stripped. Only for a row that has no problem. */
    public static String externalRef(String raw) {
        return Names.required(raw, MAX_EXTERNAL_REF_LENGTH);
    }

    /** The sex of a row without problems; a blank one is UNSPECIFIED. */
    public static String sexOf(String raw) {
        return isBlank(raw) ? Sex.UNSPECIFIED.name() : raw.strip();
    }

    /** The birth date of a row without problems. */
    public static LocalDate birthDateOf(String raw) {
        return LocalDate.parse(raw.strip(), DATE);
    }

    /** The document of a row without problems, or null when the cell is blank. */
    public static String nationalIdOf(String raw) {
        return isBlank(raw) ? null : raw.strip();
    }

    private static void text(String field, String value, int maxLength, List<Problem> problems) {
        if (isBlank(value)) {
            problems.add(new Problem(field, REQUIRED));
        } else if (hasControl(value)) {
            problems.add(new Problem(field, INVALID_FORMAT));
        } else if (value.strip().length() > maxLength) {
            problems.add(new Problem(field, TOO_LONG));
        }
    }

    private static void birthDate(String raw, Instant now, List<Problem> problems) {
        if (isBlank(raw)) {
            problems.add(new Problem("birthDate", REQUIRED));
            return;
        }
        String text = raw.strip();
        if (hasControl(text) || !DATE_SHAPE.matcher(text).matches()) {
            problems.add(new Problem("birthDate", INVALID_FORMAT));
            return;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(text, DATE);
        } catch (DateTimeParseException e) {
            problems.add(new Problem("birthDate", INVALID_DATE));
            return;
        }
        if (date.isAfter(LocalDate.ofInstant(now, HONDURAS))) {
            problems.add(new Problem("birthDate", FUTURE_BIRTH_DATE));
        } else if (date.isBefore(Student.EARLIEST_BIRTH_DATE)) {
            problems.add(new Problem("birthDate", INVALID_DATE));
        }
    }

    private static void nationalId(String raw, List<Problem> problems) {
        String text = raw.strip();
        if (hasControl(text)) {
            problems.add(new Problem("nationalId", INVALID_FORMAT));
        } else if (text.length() > MAX_NATIONAL_ID_LENGTH) {
            problems.add(new Problem("nationalId", TOO_LONG));
        } else {
            try {
                NationalId.of(text);
            } catch (InvalidStudentException e) {
                problems.add(new Problem("nationalId", INVALID_FORMAT));
            }
        }
    }

    private static boolean isSex(String value) {
        try {
            Sex.parse(value);
            return true;
        } catch (InvalidStudentException e) {
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
