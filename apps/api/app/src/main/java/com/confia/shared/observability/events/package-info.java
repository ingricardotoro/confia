/**
 * The doors through which a use case asks for a log line: {@link SecurityEventLog} for a security event
 * (session-endpoints-cookie-and-csrf design.md, decision 12) and {@link OperationalEventLog} for an
 * operational one, a line about a job of a module (background-jobs-with-db-scheduler design.md,
 * decision 17). Business code declares what happened and never imports a logging framework; the line
 * lives here.
 *
 * <p>This package carries no layer segment, like {@link com.confia.shared.observability.metrics}.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code
 * identity.application.LogIn}, which writes the {@code WARN} of the failure-spread signal after its
 * transaction commits. The consumer of {@code OperationalEventLog} is {@code
 * identity.application.RetentionPurgeBatches}, which writes the {@code retention_purge_backlog}
 * warning when a purge runs out of batches. Both classes are instantiated by the configuration of the
 * module that uses them, not registered as beans of their own.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.observability.events;
