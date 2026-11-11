package com.confia.students.application;

import com.confia.students.domain.Student;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A student as the web layer may see it. The national identity document is not here, nor can it be: the
 * view has only whether there is one and its mask.
 */
public record StudentView(UUID id, String studentCode, String firstName, String lastName, LocalDate birthDate,
        String sex, String status, boolean hasNationalId, String nationalIdMasked, Instant createdAt,
        Instant updatedAt) {

    static StudentView of(Student student) {
        return new StudentView(student.id(), student.studentCode(), student.firstName(), student.lastName(),
                student.birthDate(), student.sex().name(), student.status().name(), student.hasNationalId(),
                student.nationalIdMasked(), student.createdAt(), student.updatedAt());
    }

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "StudentView[id=" + id + "]";
    }
}
