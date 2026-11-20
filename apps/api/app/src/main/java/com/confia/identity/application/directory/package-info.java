/**
 * The read-only view of the scope of the permissions that {@code identity} publishes to the other modules:
 * {@link com.confia.identity.application.directory.OwnRecordPolicy}, which only says whether a permission of an account
 * reaches all the data of its institution or only what the account itself created. Nothing else of the roles is public.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real consumer that
 * justifies it). The consumer is {@code cashbox}, whose read use cases limit a cashier to its own cash session
 * (docs/cortes/cashbox-sessions.md) without importing the domain of {@code identity} nor asking the permission resolver,
 * which only the permission decider may do. The interface is this nested package and not {@code identity.application}
 * itself, so the use cases and the repositories of the module stay internal.
 */
@org.springframework.modulith.NamedInterface("directory")
package com.confia.identity.application.directory;
