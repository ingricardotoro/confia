/**
 * The public API of {@code notifications}: the one use case that sends a transactional e-mail
 * ({@link com.confia.notifications.application.api.SendTransactionalEmail}) and the types its
 * callers need to build a request and read the outcome.
 *
 * <p><b>{@code @NamedInterface("api")}, and who consumes it</b> (ADR-0022: every new named interface
 * names the real consumer that justifies it). The consumer is {@code identity}: the adapter that
 * delivers the password-reset link to the person (a later cut of this change, task 10) calls
 * {@code SendTransactionalEmail} and nothing else of this module. This package is a nested one so
 * the rest of {@code notifications.application}, the ports and the use case implementation, stays
 * internal to the module (design.md decision 1).
 */
@org.springframework.modulith.NamedInterface("api")
package com.confia.notifications.application.api;
