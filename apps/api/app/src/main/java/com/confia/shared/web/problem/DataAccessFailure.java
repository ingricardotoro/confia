package com.confia.shared.web.problem;

import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DataAccessException;

/**
 * What is safe to log of a failure that comes from the database: the class of the {@link
 * SQLException} (or of the cause of a Spring {@link DataAccessException} that has none) and its
 * SQLState. It is found by walking the causes, so a persistence library's own wrapper (jOOQ's
 * included) is recognised without this layer depending on it. The message of the driver is never part
 * of it, because it repeats the offending row.
 *
 * <p>The translator of the exceptions of the MVC layer and the permission decider of the filter chain
 * both describe a failed read the same way, so the description lives in one place.
 *
 * @param causeClass the class name of the SQL exception, or of the cause of the Spring exception
 * @param sqlState the SQLState, or {@value #NONE} when there is none
 */
public record DataAccessFailure(String causeClass, String sqlState) {

    /** How many causes are walked before giving up, so a cyclic chain ends. */
    static final int MAX_CAUSES = 16;

    private static final String NONE = "none";

    /** The description of {@code failure}, empty when no database failure is among its causes. */
    public static Optional<DataAccessFailure> of(Throwable failure) {
        DataAccessFailure withoutSql = null;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSES; depth++) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                return Optional.of(new DataAccessFailure(sql.getClass().getName(),
                        state == null ? NONE : state));
            }
            if (withoutSql == null && cause instanceof DataAccessException) {
                Throwable inner = cause.getCause();
                withoutSql = new DataAccessFailure(
                        inner == null ? NONE : inner.getClass().getName(), NONE);
            }
            cause = cause.getCause();
        }
        return Optional.ofNullable(withoutSql);
    }
}
