package com.confia.identity.web.wiring;

import com.confia.identity.application.CompleteSecondFactor;
import com.confia.identity.application.DescribeCurrentSession;
import com.confia.identity.application.EnrollTotpSecondFactor;
import com.confia.identity.application.LogIn;
import com.confia.identity.web.CurrentSessionController;
import com.confia.identity.web.LoginController;
import com.confia.identity.web.SecondFactorController;
import com.confia.shared.web.delay.RequiredDelayMaterializer;
import com.confia.shared.web.ratelimit.RateLimitFailures;
import com.confia.shared.web.session.SessionCookies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The public configuration of the web layer of {@code identity} for the administrative process
 * (session-tokens-and-web-layer design.md decision 13; session-endpoints-cookie-and-csrf design.md
 * decision 11; ADR-0024): it registers the controllers of the module and nothing else. They are the
 * one of the current session and the login. The use cases they take come from {@code
 * IdentityConfiguration}, which owns every use case of the module; the controllers are registered
 * here and not there because a controller is a class of the {@code web} layer, and an {@code
 * infrastructure} package that registered it would depend on the web layer it is meant to sit
 * behind. What the login takes from the edge (the delay materializer, the recorder of failures and
 * the cookie helper) is registered by the configurations of {@code shared.web.edge}.
 *
 * <p>The portal and the worker never load it: the isolation tests of the process beans fail if a
 * controller of this module reaches either context.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityWebConfiguration {

    @Bean
    CurrentSessionController currentSessionController(DescribeCurrentSession describe) {
        return new CurrentSessionController(describe);
    }

    @Bean
    LoginController loginController(LogIn logIn, RequiredDelayMaterializer delays,
            RateLimitFailures failures, SessionCookies cookies) {
        return new LoginController(logIn, delays, failures, cookies);
    }

    @Bean
    SecondFactorController secondFactorController(CompleteSecondFactor complete,
            EnrollTotpSecondFactor enroll, RequiredDelayMaterializer delays,
            RateLimitFailures failures, SessionCookies cookies) {
        return new SecondFactorController(complete, enroll, delays, failures, cookies);
    }
}
