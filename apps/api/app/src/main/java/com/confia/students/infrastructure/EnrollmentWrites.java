package com.confia.students.infrastructure;

import com.confia.students.domain.DuplicateActiveEnrollmentException;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * How a write of the enrollments that the database refused becomes a domain error. INV-31 is the partial
 * unique index {@code students_enrollment_active_uq}: when two requests race for the same student and year,
 * the one that loses reaches it, and the translation is by the {@code SQLState} {@code 23505} and the name
 * of the constraint that the server reports, never by message text, which would carry the rejected value.
 * Any other failure is rethrown untouched, so it reaches the translator of the edge as an internal error and
 * its message never reaches a client.
 */
final class EnrollmentWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    static final String ACTIVE_CONSTRAINT = "students_enrollment_active_uq";

    private EnrollmentWrites() {
    }

    /** Runs a write; the unique index of the active enrollment becomes its domain error. */
    static void translating(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            if (ACTIVE_CONSTRAINT.equals(violatedUniqueConstraint(e))) {
                throw new DuplicateActiveEnrollmentException();
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
}
