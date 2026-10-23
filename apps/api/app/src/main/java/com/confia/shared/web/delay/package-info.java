/**
 * The materializer of the delay an authentication use case computes (web-edge-foundations design.md,
 * decision 18; session-endpoints-cookie-and-csrf design.md, decision 8): the bounded semaphore a
 * login-like endpoint takes a permit from before its use case runs, and the wait, capped, after the
 * transaction committed.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of
 * {@code shared} names the real consumer that justifies it). The consumer is {@code
 * identity.web.LoginController}, which wraps the login use case in {@link
 * com.confia.shared.web.delay.RequiredDelayMaterializer#execute} and answers with {@link
 * com.confia.shared.web.delay.Delayed}.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.delay;
