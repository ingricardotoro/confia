package com.confia.catalog.infrastructure;

import com.confia.catalog.domain.DuplicateCodeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What every adapter of the catalog shares: how a write the database refused becomes a domain error, and how an
 * instant crosses to a {@code timestamptz} column and back.
 *
 * <p>The translation is by the {@code SQLState} {@code 23505} (unique violation) and the name of the constraint or
 * index that the server reports, never by message text. A unique violation of any other kind, and every other failure,
 * is rethrown untouched, so it reaches the translator of the edge as an internal error and its message never reaches a
 * client.
 */
final class CatalogWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CODE_UNIQUE_INDEX = "catalog_payment_concept_code_uq";

    private CatalogWrites() {
    }

    /** Runs a write; a uniqueness the catalog owns becomes its domain error. */
    static void translating(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            String constraint = violatedUniqueConstraint(e);
            if (CODE_UNIQUE_INDEX.equals(constraint)) {
                throw new DuplicateCodeException();
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
