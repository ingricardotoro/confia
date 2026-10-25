package com.confia.shared.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

/**
 * Denies the {@code DEBUG} and {@code TRACE} events of the few loggers that write raw request
 * headers, bodies or query strings, at whatever level someone configures, so an {@code
 * Authorization} header, a cookie, a body or a token in a query string can never reach a log by
 * turning a level up (web-edge-foundations design.md, decision 22; CLAUDE.md regla 11). {@code
 * INFO}, {@code WARN} and {@code ERROR} of those loggers still pass, with one exception: the
 * {@code INFO} of {@link #INFO_PROTECTED_LOGGERS}, which is written for any client with no session.
 *
 * <p><b>What is covered, and how it is known.</b> {@code SensitiveDataLoggingTest} proves each
 * prefix the process really logs through, by removing the guard and showing the secret appear:
 * the bearer token and the body through {@code org.apache.coyote}, the cookie through {@code
 * org.apache.tomcat.util.http}, and a query string through {@code org.springframework.security}.
 * {@code org.apache.tomcat.util.net}, {@code org.springframework.web.servlet.DispatcherServlet},
 * {@code org.springframework.web.servlet.mvc.method.annotation} and {@code
 * org.springframework.http.converter} are <b>preventive</b>: they are the loggers documented to
 * write request details, and the test does not exercise them live (the harness has no {@code
 * @RequestBody} endpoint, so the message converters never run).
 *
 * <p><b>What is not covered.</b> Anything that is not a logger of that list: another library, or
 * code that logs a value at {@code INFO} or above. jOOQ statements also carry bind values at
 * {@code DEBUG}: {@code org.jooq} is in the list as defence in depth, and {@code
 * Settings.withExecuteLogging(false)} on the production {@code DSLContext} is what holds whatever
 * the logger. The data-access stack (driver, pool, Spring JDBC and transactions, db-scheduler) is
 * listed because the worker process receives a {@code DataSource} right after this guard exists
 * (background-jobs-with-db-scheduler, condition 4 of the foundations plan).
 *
 * <p>It is a Logback turbo filter, which is consulted before an event exists, so a denied event is
 * never formatted. It is installed by {@link SensitiveLogGuardInitializer}, an application context
 * initializer that every process entry point adds: that runs after Spring Boot's logging system is
 * initialized (which resets the filters) and before the context creates its first bean, so no bean,
 * a {@code DataSource} included, can log before the guard exists. It is withdrawn when the context
 * closes, and installing it removes any copy a context that never closed left behind, so a second
 * context in the same JVM does not stack a second copy. With another logging backend it does
 * nothing, and the test that proves the guard fails there.
 */
public final class SensitiveLogGuard extends TurboFilter {

    /** The closed list of logger prefixes that record headers, bodies or query strings. */
    static final List<String> PROTECTED_PREFIXES = List.of(
            "org.springframework.web.servlet.mvc.method.annotation",
            "org.springframework.http.converter", "org.apache.coyote",
            "org.apache.tomcat.util.net",
            // Found by SensitiveDataLoggingTest: the cookie processor logs a raw Cookie value at
            // DEBUG, which decision 22 had not listed.
            "org.apache.tomcat.util.http",
            // Found by SensitiveDataLoggingTest (I-1b of the review of 2.2b): Spring Security logs
            // the request URL, query string included, at DEBUG and TRACE, and the dispatcher is
            // documented to log request parameters.
            "org.springframework.security",
            "org.springframework.web.servlet.DispatcherServlet",
            // Review of 2.4b: the resolvers of exceptions and the invokers of handlers record the
            // exception with its rejected values, or the arguments of the call, at DEBUG and TRACE.
            "org.springframework.web.servlet.mvc.support",
            "org.springframework.web.servlet.mvc.annotation",
            "org.springframework.web.servlet.handler", "org.springframework.web.method",
            // The data-access stack (design.md decision 13; probe P-11). The PostgreSQL driver
            // writes the bound parameters of a statement at FINEST, so a value passed to
            // set_config or to a query can reach a log; WorkerSensitiveLoggingIT proves which of
            // these really leak and which are preventive.
            "org.jooq", "org.postgresql", "com.zaxxer.hikari", "org.springframework.jdbc",
            "org.springframework.transaction", "com.github.kagkarlsson");

    /**
     * The exact loggers whose {@code INFO} is denied as well (review S-3 of the close of
     * web-edge-foundations, {@code MalformedRequestLoggingTest}): Tomcat logs the first malformed
     * request of a connection processor at {@code INFO} with the whole request target in the
     * exception, query string included, and any client can send one. Its {@code WARN} and {@code
     * ERROR} still pass.
     */
    static final List<String> INFO_PROTECTED_LOGGERS =
            List.of("org.apache.coyote.http11.Http11Processor");

    /** The protected prefixes, for the tests that raise each of those loggers to {@code TRACE}. */
    public static List<String> protectedPrefixes() {
        return PROTECTED_PREFIXES;
    }

    /** Whether an event of {@code level} from {@code loggerName} must be dropped. */
    static boolean denies(Level level, String loggerName) {
        if (level.toInt() <= Level.DEBUG_INT && isProtected(loggerName)) {
            return true;
        }
        return level.toInt() <= Level.INFO_INT && INFO_PROTECTED_LOGGERS.contains(loggerName);
    }

    private static boolean isProtected(String loggerName) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (loggerName.equals(prefix) || loggerName.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format,
            Object[] params, Throwable throwable) {
        return level != null && denies(level, logger.getName()) ? FilterReply.DENY
                : FilterReply.NEUTRAL;
    }

    /** Puts this guard in the Logback filter chain, replacing any copy a late-closed context left. */
    void install() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getTurboFilterList().removeIf(
                    filter -> filter instanceof SensitiveLogGuard && filter != this);
            if (!context.getTurboFilterList().contains(this)) {
                setContext(context);
                context.addTurboFilter(this);
            }
        }
    }

    /** Withdraws this guard from the Logback filter chain. */
    void uninstall() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getTurboFilterList().remove(this);
        }
    }
}
