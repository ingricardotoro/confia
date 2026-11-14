/**
 * The {@code notifications} capability: the single door through which the system sends transactional
 * e-mail (ADR-0029, concreting ADR-0014 and {@code docs/08} section 8.4; change {@code
 * transactional-email-adapter}, F0 change 14a).
 *
 * <p>This first cut holds the module's {@code domain} (the composed message and the closed list of
 * failure classes) and its public API, the named interface {@code api} of {@code
 * application.api}: one use case that sends a template of a closed list to one recipient, with the
 * parameters of that template. There is no operation that takes a subject or a body. The SMTP
 * transport, the delivery log and the worker wiring arrive in later cuts of the same change.
 *
 * <p><b>No {@code web} package</b> (ADR-0020): the module exposes no HTTP surface. It is loaded only
 * by the worker process; the administrative and portal processes never contain it. Only {@code
 * notifications.infrastructure} may depend on {@code jakarta.mail}, {@code org.eclipse.angus} or
 * {@code org.springframework.mail} ({@code MailClientConfinementTest}, BI150).
 *
 * <p>Out of scope here, with F6 as the destination: asynchronous bounces and complaints, a
 * suppression list, unsubscribe, consent, a per-recipient frequency cap, HTML messages, templates in
 * the database and purging the delivery log.
 */
package com.confia.notifications;
