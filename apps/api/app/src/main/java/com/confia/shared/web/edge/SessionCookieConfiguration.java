package com.confia.shared.web.edge;

import com.confia.shared.security.csrf.CsrfTokens;
import com.confia.shared.web.session.SessionCookies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The cookie helper of the administrative session (session-endpoints-cookie-and-csrf design.md,
 * decision 5; ADR-0024). Imported explicitly by {@code AdminApplication}; the portal and the worker
 * never load it, and {@code ProcessBeanPolicy} lists its package as forbidden for both. It has no
 * property: the cookies are always {@code Secure} and their names are fixed, so there is no setting
 * that could weaken them.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfiguration {

    @Bean
    SessionCookies sessionCookies(CsrfTokens tokens) {
        return new SessionCookies(tokens);
    }
}
