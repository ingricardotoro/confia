package com.confia.students.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * A student of an institution. The national identity document is not a member: the aggregate only knows
 * whether there is one: no character of it is kept in plain. The code never
 * changes after the creation. A student is never deleted: it is made {@link StudentStatus#INACTIVE}.
 */
public record Student(UUID id, String studentCode, String firstName, String lastName, LocalDate birthDate,
        Sex sex, StudentStatus status, boolean hasNationalId, Instant createdAt, Instant updatedAt) {

    /** Nobody in the registry was born before this day (it catches typing mistakes). */
    public static final LocalDate EARLIEST_BIRTH_DATE = LocalDate.of(1990, 1, 1);

    private static final ZoneId HONDURAS = ZoneId.of("America/Tegucigalpa");

    public Student {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sex, "sex");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(birthDate, "birthDate");
        studentCode = StudentCode.typed(studentCode);
        firstName = Names.required(firstName, Names.MAX_NAME_LENGTH);
        lastName = Names.required(lastName, Names.MAX_NAME_LENGTH);
    }

    /**
     * A new, active student. The birth date is judged against the day it is in Honduras at {@code now}:
     * not in the future and not before {@link #EARLIEST_BIRTH_DATE}.
     */
    public static Student create(UUID id, String studentCode, String firstName, String lastName,
            LocalDate birthDate, String sex, NationalId nationalId, Instant now) {
        return new Student(id, studentCode, firstName, lastName, checkedBirthDate(birthDate, now),
                Sex.parse(sex), StudentStatus.ACTIVE, nationalId != null, now, now);
    }

    /** Replaces every editable member; the identifier, the code and the creation instant stay. */
    public Student edit(String newFirstName, String newLastName, LocalDate newBirthDate, String newSex,
            String newStatus, NationalIdChange document, Instant now) {
        boolean present = switch (document) {
            case NationalIdChange.Keep keep -> hasNationalId;
            case NationalIdChange.Remove remove -> false;
            case NationalIdChange.Replace replace -> true;
        };
        return new Student(id, studentCode, newFirstName, newLastName, checkedBirthDate(newBirthDate, now),
                Sex.parse(newSex), StudentStatus.parse(newStatus), present, createdAt, now);
    }

    private static LocalDate checkedBirthDate(LocalDate birthDate, Instant now) {
        if (birthDate == null || birthDate.isBefore(EARLIEST_BIRTH_DATE)
                || birthDate.isAfter(LocalDate.ofInstant(now, HONDURAS))) {
            throw new InvalidStudentException();
        }
        return birthDate;
    }

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "Student[id=" + id + "]";
    }
}
