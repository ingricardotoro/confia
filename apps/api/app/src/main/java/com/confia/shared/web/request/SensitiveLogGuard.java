package com.confia.shared.web.request;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.util.List;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/**
 * Denies the {@code DEBUG} and {@code TRACE} events of the few loggers that write raw request
 * headers, bodies or query strings, at whatever level someone configures, so an {@code
 * Authorization} header, a cookie, a body or a token in a query string can never reach a log by
 * turning a level up (web-edge-foundations design.md, decision 22; CLAUDE.md regla 11). {@code
 * INFO}, {@code WARN} and {@code ERROR} of those loggers still pass.
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
 * code that logs a value at {@code INFO} or above. jOOQ statements, which carry bind values at
 * {@code DEBUG}, are not handled here but by {@code Settings.withExecuteLogging(false)} on the
 * production {@code DSLContext}, which holds whatever the logger. The worker process has neither
 * this guard nor a {@code DataSource} yet; the change that gives it one must carry the protection
 * with it (foundations-plan, acceptance condition owned by change 9).
 *
 * <p>It is a Logback turbo filter, which is consulted before an event exists, so a denied event is
 * never formatted. It installs itself when its bean is created, which is after Spring Boot's
 * logging system is initialized, and removes itself when the context closes, so a second context
 * in the same JVM does not stack a second copy. With another logging backend it does nothing, and
 * the test that proves the guard fails there.
 */
public final class SensitiveLogGuard extends TurboFilter implements InitializingBean,
        DisposableBean {

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
            "org.springframework.web.servlet.DispatcherServlet");

    /** Whether an event of {@code level} from {@code loggerName} must be dropped. */
    static boolean denies(Level level, String loggerName) {
        return level.toInt() <= Level.DEBUG_INT && isProtected(loggerName);
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

    @Override
    public void afterPropertiesSet() {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (factory instanceof LoggerContext context && !context.getTurboFilterList().contains(this)) {
            setContext(context);
            context.addTurboFilter(this);
        }
    }

    @Override
    public void destroy() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getTurboFilterList().remove(this);
        }
    }
}
