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
 * headers or bodies, at whatever level someone configures, so an {@code Authorization} header, a
 * cookie or a body can never reach a log by turning a level up (web-edge-foundations design.md,
 * decision 22; CLAUDE.md regla 11). {@code INFO}, {@code WARN} and {@code ERROR} of those loggers
 * still pass.
 *
 * <p>It is a Logback turbo filter, which is consulted before an event exists, so a denied event is
 * never formatted. It installs itself when its bean is created, which is after Spring Boot's
 * logging system is initialized, and removes itself when the context closes, so a second context
 * in the same JVM does not stack a second copy. With another logging backend it does nothing, and
 * the test that proves the guard fails there.
 */
public final class SensitiveLogGuard extends TurboFilter implements InitializingBean,
        DisposableBean {

    /** The closed list of logger prefixes that record headers or bodies. */
    static final List<String> PROTECTED_PREFIXES = List.of(
            "org.springframework.web.servlet.mvc.method.annotation",
            "org.springframework.http.converter", "org.apache.coyote",
            "org.apache.tomcat.util.net",
            // Found by SensitiveDataLoggingTest: the cookie processor logs a raw Cookie value at
            // DEBUG, which decision 22 had not listed.
            "org.apache.tomcat.util.http");

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
