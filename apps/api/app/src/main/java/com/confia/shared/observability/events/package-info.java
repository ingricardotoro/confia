/**
 * The door through which a use case asks for the log line of a security event
 * (session-endpoints-cookie-and-csrf design.md, decision 12). Business code declares what happened
 * and never imports a logging framework; the line lives here.
 *
 * <p>This package carries no layer segment, like {@link com.confia.shared.observability.metrics}.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code
 * identity.application.LogIn}, which writes the {@code WARN} of the failure-spread signal after its
 * transaction commits. The class is instantiated by that use case's configuration, not registered
 * as a bean of its own.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.observability.events;
