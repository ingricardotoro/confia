package com.confia.charges.infrastructure;

import com.confia.charges.domain.DuplicatePlanItemException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What every adapter of the charge plans shares: how a write the database refused becomes a domain error, and how an
 * instant crosses to a {@code timestamptz} column and back.
 *
 * <p>The translation is by the {@code SQLState} ({@code 23505}, unique violation) and the name of the constraint that the
 * server reports, never by message text. A violation of any other kind, and every other failure, is rethrown untouched, so
 * it reaches the translator of the edge as an internal error and its message never reaches a client.
 */
final class ChargesWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String ITEM_CONCEPT_UNIQUE = "charges_plan_item_concept_uq";

    private ChargesWrites() {
    }

    /** Runs a write; the uniqueness of a concept in a plan becomes its domain error. */
    static void translating(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            if (ITEM_CONCEPT_UNIQUE.equals(violatedConstraint(e, UNIQUE_VIOLATION))) {
                throw new DuplicatePlanItemException();
            }
            throw e;
        }
    }

    private static String violatedConstraint(Throwable failure, String sqlState) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres
                    && sqlState.equals(postgres.getSQLState())
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
