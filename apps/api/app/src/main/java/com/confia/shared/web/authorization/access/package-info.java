/**
 * What a controller of another module may take from the authorization of the administrative chain without reaching
 * into the rest of {@code shared.web.authorization}: the permissions the decider resolved for the request
 * ({@link com.confia.shared.web.authorization.access.ResolvedPermissions}) and the refusal that ends a request with the
 * audited {@code 403} of a missing permission ({@link com.confia.shared.web.authorization.access.AccessRefusals}).
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface of {@code shared} names
 * the real consumer that justifies it). The consumer is {@code imports.web.ImportController}, whose history lists the
 * batches of the kinds the account may read: it refines the decision of the edge for the kind asked for (a guardians
 * kind also needs {@code guardians:read}) from the permissions the decider already resolved, so the permissions are
 * resolved once per request, and it refuses with the same exception the decider throws, so the denial is audited the
 * same way (docs/cortes/imports-guardians.md). The decider, the recorder and the exception itself stay internal.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.web.authorization.access;
