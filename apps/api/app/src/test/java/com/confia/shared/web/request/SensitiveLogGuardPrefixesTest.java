package com.confia.shared.web.request;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
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
}
