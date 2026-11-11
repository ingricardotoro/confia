package com.confia.students.infrastructure;

import com.confia.students.domain.DuplicateNationalIdException;
import com.confia.students.domain.DuplicateStudentCodeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What the adapter of the registry shares: how a write the database refused becomes a domain error, and
 * how an instant crosses to a {@code timestamptz} column and back.
 *
 * <p>The translation is by the {@code SQLState} {@code 23505} (unique violation) and the name of the
 * constraint that the server reports, never by message text, which would carry the rejected value. A
 * unique violation of any other kind, and every other failure, is rethrown untouched, so it reaches the
 * translator of the edge as an internal error and its message never reaches a client.
 */
final class StudentWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    static final String CODE_CONSTRAINT = "students_student_code_uq";
    static final String NATIONAL_ID_CONSTRAINT = "students_student_national_id_uq";

    private StudentWrites() {
    }

    /** Runs a write; a uniqueness the registry owns becomes its domain error. */
    static void translating(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            String constraint = violatedUniqueConstraint(e);
            if (CODE_CONSTRAINT.equals(constraint)) {
                throw new DuplicateStudentCodeException();
            }
            if (NATIONAL_ID_CONSTRAINT.equals(constraint)) {
                throw new DuplicateNationalIdException();
            }
            throw e;
        }
    }

    private static String violatedUniqueConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres
                    && UNIQUE_VIOLATION.equals(postgres.getSQLState())
                    && postgres.getServerErrorMessage() != null) {
                return postgres.getServerErrorMessage().getConstraint();
            }
        }
        return null;
    }

    static OffsetDateTime toDatabase(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant fromDatabase(OffsetDateTime value) {
        return value.toInstant();
    }
}
