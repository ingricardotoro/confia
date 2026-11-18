package com.confia.catalog.infrastructure;

import com.confia.catalog.domain.DuplicateCodeException;
import com.confia.catalog.domain.DuplicateTaxRateCodeException;
import com.confia.catalog.domain.PriceValidityOverlapException;
import com.confia.catalog.domain.TaxRatePeriodOverlapException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What every adapter of the catalog shares: how a write the database refused becomes a domain error, and how an
 * instant crosses to a {@code timestamptz} column and back.
 *
 * <p>The translation is by the {@code SQLState} ({@code 23505}, unique violation, and {@code 23P01}, exclusion
 * violation) and the name of the constraint or index that the server reports, never by message text. A violation of any
 * other kind, and every other failure, is rethrown untouched, so it reaches the translator of the edge as an internal
 * error and its message never reaches a client.
 */
final class CatalogWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String CODE_UNIQUE_INDEX = "catalog_payment_concept_code_uq";
    private static final String VALIDITY_EXCLUSION = "catalog_price_list_item_validity_excl";
    private static final String TAX_RATE_CODE_UNIQUE_INDEX = "catalog_tax_rate_code_uq";
    private static final String TAX_RATE_VALIDITY_EXCLUSION = "catalog_tax_rate_period_validity_excl";

    private CatalogWrites() {
    }

    /** Runs a write; a uniqueness or an exclusion the catalog owns becomes its domain error. */
    static void translating(Runnable write) {
        try {
            write.run();
        } catch (DataAccessException e) {
            if (CODE_UNIQUE_INDEX.equals(violatedConstraint(e, UNIQUE_VIOLATION))) {
                throw new DuplicateCodeException();
            }
            if (TAX_RATE_CODE_UNIQUE_INDEX.equals(violatedConstraint(e, UNIQUE_VIOLATION))) {
                throw new DuplicateTaxRateCodeException();
            }
            String exclusion = violatedConstraint(e, EXCLUSION_VIOLATION);
            if (VALIDITY_EXCLUSION.equals(exclusion)) {
                throw new PriceValidityOverlapException();
            }
            if (TAX_RATE_VALIDITY_EXCLUSION.equals(exclusion)) {
                throw new TaxRatePeriodOverlapException();
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
