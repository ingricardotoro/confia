package com.confia.cashbox.infrastructure;

import com.confia.cashbox.application.RequestKeyTakenException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.jooq.exception.DataAccessException;
import org.postgresql.util.PSQLException;

/**
 * What every adapter of the cash sessions shares: how a write that lost a race becomes the signal to start again, and how an
 * instant crosses to a {@code timestamptz} column and back. The translation is by the {@code SQLState} ({@code 23505}, unique
 * violation) and the name of the constraint that the server reports, never by message text. Any other failure is rethrown
 * untouched, so it reaches the translator of the edge as an internal error and its message never reaches a client.
 */
final class CashboxWrites {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final Set<String> RACE_CONSTRAINTS = Set.of("cashbox_session_key_uq", "cashbox_session_one_open_uq",
            "cashbox_movement_key_uq", "cashbox_session_close_key_uq", "cashbox_session_approval_key_uq");

    private CashboxWrites() {
    }

    /** Runs an insert; the loss of a race on a request key or on the one-open-session index becomes its signal. */
    static void inserting(Runnable insert) {
        try {
            insert.run();
        } catch (DataAccessException e) {
            String constraint = violatedConstraint(e);
            if (constraint != null && RACE_CONSTRAINTS.contains(constraint)) {
                throw new RequestKeyTakenException();
            }
            throw e;
        }
    }

    private static String violatedConstraint(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException postgres && UNIQUE_VIOLATION.equals(postgres.getSQLState())
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
