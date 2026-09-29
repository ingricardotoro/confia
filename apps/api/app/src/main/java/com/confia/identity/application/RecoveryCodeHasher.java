package com.confia.identity.application;

import com.confia.identity.domain.PlainRecoveryCode;
import com.confia.identity.domain.StoredRecoveryCodeHash;

/**
 * Verifies and computes Argon2id hashes of MFA recovery codes
 * (column-encryption-and-mfa-totp design.md, decision 5 / proposal.md D5). {@code
 * com.confia.identity.infrastructure.BouncyCastleRecoveryCodeHasher} is its one adapter.
 *
 * <p><b>A port of its own, never a reuse of {@link PasswordHasher}.</b> {@code PasswordHasher} is
 * typed over {@code PlainPassword} and {@code StoredPasswordHash}, two names tied semantically to
 * "password"; forcing them to also mean "recovery code" would be exactly the defect this module
 * already avoids elsewhere (proposal.md, D5). The clean reuse is the Argon2id profile, the PHC
 * codec and the low-level hasher — same pepper, same {@code $argon2id$} format — behind this
 * separate port, never {@code PasswordHasher} reopened. This port is exclusive to MFA recovery
 * codes; the password-recovery token of the {@code password-recovery-token} change is a distinct
 * mechanism with its own hashing decision in its own proposal.
 */
public interface RecoveryCodeHasher {

    boolean matches(PlainRecoveryCode code, StoredRecoveryCodeHash hash);

    StoredRecoveryCodeHash hash(PlainRecoveryCode code);
}
