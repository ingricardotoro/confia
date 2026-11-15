/**
 * The write side of the bulk import of guardians and of their links that {@code guardians} publishes to the other
 * modules: {@link com.confia.guardians.application.importing.GuardianImportGateway}, {@link
 * com.confia.guardians.application.importing.GuardianLinkImportGateway} and the small records they exchange. Nothing
 * else of the registry is public.
 *
 * <p><b>{@code @NamedInterface}, and who consumes it</b> (ADR-0022: every new named interface names the real
 * consumer that justifies it). The consumer is {@code imports}, whose use cases read a CSV file of guardians or of
 * links, ask these ports to judge every row with the rules of the registry and to register the valid ones through the
 * same use cases as the hand registration ({@code ManageGuardians} and {@code ManageGuardianLinks}: code, encrypted
 * document and RTN with the blind index, audit, the lock of the student and INV-32), without importing the domain of
 * {@code guardians} nor reading its tables (docs/cortes/imports-guardians.md). The interface is this nested package
 * and not {@code guardians.application} itself, so the use cases and the repositories of the registry stay internal.
 */
@org.springframework.modulith.NamedInterface
package com.confia.guardians.application.importing;
