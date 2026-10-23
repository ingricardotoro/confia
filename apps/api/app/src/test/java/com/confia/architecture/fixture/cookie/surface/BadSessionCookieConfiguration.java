package com.confia.architecture.fixture.cookie.surface;

import org.springframework.core.env.Environment;

/**
 * Deliberate violation fixture (session-endpoints-cookie-and-csrf design.md decision 5; specs/web-edge,
 * scenario [WE64]): a cookie configuration that reads a property to decide whether the cookies are
 * {@code Secure}. The cookie helper has no such property, and the rule that keeps it that way must
 * reject this class. Physically kept under {@code architecture.fixture.cookie.surface}, so a real
 * production class never becomes it. Permanent, never removed: it is what proves the rule rejects
 * something (ADR-0018).
 */
public final class BadSessionCookieConfiguration {

    private final Environment environment;

    public BadSessionCookieConfiguration(Environment environment) {
        this.environment = environment;
    }

    public boolean secure() {
        return environment.getProperty("confia.web.session-cookie.secure", Boolean.class, true);
    }
}
