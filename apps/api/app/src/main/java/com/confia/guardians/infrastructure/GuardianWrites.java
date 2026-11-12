package com.confia.guardians.infrastructure;

import com.confia.guardians.domain.DuplicateNationalIdException;
import com.confia.guardians.domain.InvalidResponsibilitySplitException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What the adapters of the guardians share: how a write the database refused becomes a domain error, and how an
 * instant crosses to a {@code timestamptz} column and back.
 *
 * <p>The translation is by the {@code SQLState} {@code 23505} (unique violation) and the name of the constraint that
 * the server reports, never by message text, which would carry the rejected value. A unique violation of any other
 * kind, and every other failure, is rethrown untouched, so it reaches the translator of the edge as an internal error
 * and its message never reaches a client.
 */
final class GuardianWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    static final String NATIONAL_ID_CONSTRAINT = "guardians_guardian_national_id_uq";
    static final String CURRENT_LINK_CONSTRAINT = "guardians_guardian_student_current_uq";
    static final String PRIMARY_CONSTRAINT = "guardians_guardian_student_primary_uq";
    static final String BILLING_CONSTRAINT = "guardians_guardian_student_billing_uq";

    private GuardianWrites() {
    }

    /** Runs a write; a uniqueness the guardians own becomes its domain error. */
    static void translating(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            String constraint = violatedUniqueConstraint(e);
            if (NATIONAL_ID_CONSTRAINT.equals(constraint)) {
                throw new DuplicateNationalIdException();
            }
            if (CURRENT_LINK_CONSTRAINT.equals(constraint)) {
                throw new InvalidResponsibilitySplitException(InvalidResponsibilitySplitException.DUPLICATE_GUARDIAN);
            }
            if (PRIMARY_CONSTRAINT.equals(constraint)) {
                throw new InvalidResponsibilitySplitException(
                        InvalidResponsibilitySplitException.MULTIPLE_PRIMARY_PAYER);
            }
            if (BILLING_CONSTRAINT.equals(constraint)) {
                throw new InvalidResponsibilitySplitException(
                        InvalidResponsibilitySplitException.MULTIPLE_BILLING_RECIPIENT);
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
