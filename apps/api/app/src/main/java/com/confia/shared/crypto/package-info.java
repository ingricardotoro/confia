/**
 * The envelope-encryption port, orchestrator and value objects for column-level encryption
 * (column-encryption-and-mfa-totp design.md, decision 2; ADR-0023). Owns {@code
 * shared_data_encryption_key}, gestionada por su único adaptador jOOQ en {@link
 * com.confia.shared.infrastructure} (design.md decision 2, why the adapter lives directly there
 * and not in a nested {@code shared.crypto.infrastructure} package: {@code
 * TableOwnershipByModuleTest} derives the required generated-table-type prefix from the segment
 * immediately before the first layer segment, and that segment must stay {@code shared} for this
 * table's {@code shared_} prefix, per ADR-0015 rule 3).
 *
 * <p>This package carries no layer segment ({@code domain}, {@code application}, {@code
 * infrastructure} or {@code web}): the exact same pattern {@code shared.security} and {@code
 * shared.audit} already establish (design.md, decision 2, point 2). {@link
 * com.confia.architecture.LayeredArchitectureTest} therefore treats it as outside the layered
 * architecture entirely.
 *
 * <p>{@code @NamedInterface} (ADR-0022's own precedent, extended here to a third {@code shared}
 * sub-package): {@code identity.application} and {@code identity.infrastructure} need {@link
 * ColumnEncryptionService} to encrypt and decrypt the TOTP secret from cut C2 onward, which is
 * exactly the cross-module-import shape ADR-0022 already solved for {@code TransactionRunner} and
 * {@code AuditLogWriter}.
 */
@org.springframework.modulith.NamedInterface
package com.confia.shared.crypto;
