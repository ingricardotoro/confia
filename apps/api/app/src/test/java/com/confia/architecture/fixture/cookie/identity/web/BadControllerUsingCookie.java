package com.confia.architecture.fixture.cookie.identity.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * Deliberate violation fixture (session-endpoints-cookie-and-csrf design.md, decisions 5 and 20;
 * specs/build-integrity, requirement "El HMAC de CSRF vive en shared.security y el uso de Cookie se
 * confina al ayudante de cookies", scenario BI47): a controller of {@code identity.web} that writes
 * a session cookie with {@code jakarta.servlet.http.Cookie} instead of going through the cookie
 * helper, which would leave its attributes to the controller. Physically kept under {@code
 * architecture.fixture.cookie.identity}, so a real production class never becomes it. Permanent,
 * never removed: it is what proves the confinement rule rejects something (ADR-0018).
 */
public final class BadControllerUsingCookie {

    @PostMapping("/bad/cookie")
    public String issue(HttpServletResponse response) {
        response.addCookie(new Cookie("SESSION", "value"));
        return "issued";
    }
}
