package com.confia.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The loggers of the classes that resolve an exception and invoke a handler record the exception
 * with its rejected values, or the arguments of the call, at DEBUG and TRACE (web-edge-foundations
 * design.md, decisions 9 and 22). Each must be denied below INFO and pass at WARN and above.
 */
class SensitiveLogGuardPrefixesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",
            "org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod",
            "org.springframework.web.servlet.mvc.support.DefaultHandlerExceptionResolver",
            "org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver",
            "org.springframework.web.servlet.handler.HandlerExceptionResolverComposite",
            "org.springframework.web.method.support.InvocableHandlerMethod",
            "org.springframework.web.method.annotation.ModelAttributeMethodProcessor"})
    void aResolverOrInvokerOfTheMvcLayerNeverLogsBelowInfo(String logger) {
        assertThat(SensitiveLogGuard.denies(Level.DEBUG, logger)).isTrue();
        assertThat(SensitiveLogGuard.denies(Level.TRACE, logger)).isTrue();
        assertThat(SensitiveLogGuard.denies(Level.WARN, logger)).isFalse();
    }

    /**
     * The loggers of the data-access stack (background-jobs-with-db-scheduler design.md decision 13;
     * specs BI88 and BI110): the PostgreSQL driver writes the bound parameters of a statement at
     * {@code FINEST}, jOOQ writes its statements, and the pool, Spring JDBC, Spring transactions and
     * db-scheduler write connection and statement details. Each, and everything below it, is denied
     * at DEBUG and TRACE and passes at INFO and above.
     */
    @ParameterizedTest
    @ValueSource(strings = {"org.jooq", "org.postgresql", "com.zaxxer.hikari",
            "org.springframework.jdbc", "org.springframework.transaction",
            "com.github.kagkarlsson"})
    void theDataAccessStackNeverLogsBelowInfo(String prefix) {
        for (String logger : List.of(prefix, prefix + ".Anything", prefix + ".sub.package.Deep")) {
            assertThat(SensitiveLogGuard.denies(Level.DEBUG, logger)).as("DEBUG " + logger).isTrue();
            assertThat(SensitiveLogGuard.denies(Level.TRACE, logger)).as("TRACE " + logger).isTrue();
            assertThat(SensitiveLogGuard.denies(Level.INFO, logger)).as("INFO " + logger).isFalse();
            assertThat(SensitiveLogGuard.denies(Level.WARN, logger)).as("WARN " + logger).isFalse();
            assertThat(SensitiveLogGuard.denies(Level.ERROR, logger)).as("ERROR " + logger).isFalse();
        }
    }

    /** A name that only shares text with a prefix, or a sibling package, is not covered. */
    @ParameterizedTest
    @ValueSource(strings = {"org.jooqx.Anything", "org.postgresqlx", "org.postgres.Anything",
            "com.zaxxer.hikarix.Pool", "com.zaxxer", "org.springframework.jdbcx.Template",
            "org.springframework.transactional.Thing", "com.github.kagkarlssonx.Scheduler",
            "com.github.kagkarlsson2"})
    void aSimilarLoggerNameThatIsNotCoveredPassesAtEveryLevel(String logger) {
        for (Level level : List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN)) {
            assertThat(SensitiveLogGuard.denies(level, logger)).as(level + " " + logger).isFalse();
        }
    }
}
