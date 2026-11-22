package com.confia.payments.infrastructure;

import com.confia.payments.application.RequestKeyTakenException;
import com.confia.payments.domain.DuplicateTransferReferenceException;
import com.confia.payments.domain.InvalidPaymentsValueException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What every adapter of the payments shares: how a write the database refused becomes a domain error or the signal to start
 * again, and how an instant crosses to a {@code timestamptz} column and back. The translation is by the {@code SQLState}
 * ({@code 23505}, unique violation, and {@code 23503}, foreign key violation) and the name of the constraint that the server
 * reports, never by message text. Any other failure is rethrown untouched, so it reaches the translator of the edge as an
 * internal error and its message never reaches a client.
 */
final class PaymentsWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final Set<String> RACE_CONSTRAINTS = Set.of("payments_payment_key_uq", "payments_payment_decision_key_uq");
    private static final String REFERENCE_CONSTRAINT = "payments_payment_transfer_ref_uq";
    private static final String GUARDIAN_CONSTRAINT = "payments_payment_guardian_fk";

    private PaymentsWrites() {
    }

    /**
     * Runs an insert; the loss of a race on a request key becomes its signal, a repeated bank reference its domain error, and a
     * guardian that is not one of the institution a validation error.
     */
    static void inserting(Runnable insert) {
        try {
            insert.run();
        } catch (DataAccessException e) {
            String unique = violatedConstraint(e, UNIQUE_VIOLATION);
            if (unique != null && RACE_CONSTRAINTS.contains(unique)) {
                throw new RequestKeyTakenException();
            }
            if (REFERENCE_CONSTRAINT.equals(unique)) {
                throw new DuplicateTransferReferenceException();
            }
            if (GUARDIAN_CONSTRAINT.equals(violatedConstraint(e, FOREIGN_KEY_VIOLATION))) {
                throw new InvalidPaymentsValueException();
            }
            throw e;
        }
    }

    private static String violatedConstraint(Throwable failure, String sqlState) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && sqlState.equals(postgres.getSQLState())
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
