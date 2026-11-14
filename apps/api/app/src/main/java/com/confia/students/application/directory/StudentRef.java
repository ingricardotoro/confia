package com.confia.students.application.directory;

import java.util.Objects;
import java.util.UUID;

/**
 * A student as the other modules may see it: who it is and whether it is in use. It carries the code and the
 * names (the guardians' screens show them) and nothing else: never the birth date and never the national identity
 * document, of which not even its existence is published.
 */
public record StudentRef(UUID studentId, String studentCode, String firstName, String lastName, boolean active) {

    public StudentRef {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(studentCode, "studentCode");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
    }

    /** Personal data of a minor never reaches a log through this method. */
    @Override
    public String toString() {
        return "StudentRef[id=" + studentId + "]";
    }
}
