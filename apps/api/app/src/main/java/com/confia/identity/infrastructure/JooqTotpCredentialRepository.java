package com.confia.identity.infrastructure;

import static confia.generated.jooq.tables.IdentityMfaTotpCredential.IDENTITY_MFA_TOTP_CREDENTIAL;

import com.confia.identity.application.TotpCredentialRepository;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.domain.TotpCredential;
import com.confia.kernel.InstitutionId;
import java.util.Optional;
import org.jooq.DSLContext;

/**
 * The single jOOQ adapter of {@link TotpCredentialRepository}
 * (column-encryption-and-mfa-totp design.md, decision 3, §4.2; ADR-0015 rule 4, R1: jOOQ confined
 * to {@code infrastructure}). {@code final}, with an explicit constructor over {@link DSLContext}
 * and no Spring annotation, the same pattern {@code JooqStaffAccountRepository} already
 * established.
 *
 * <p><b>Never opens a transaction of its own</b> (ADR-0015 rule 7, R3): every method assumes it
 * runs inside the transaction {@link com.confia.shared.security.TransactionRunner} already opened.
 *
 * <p><b>{@link #acceptCounter} is the single conditional statement design.md decision 7 fixes
 * exactly</b>: an {@code UPDATE ... WHERE ... AND last_accepted_counter < candidateCounter}, never a
 * {@code SELECT} followed by a separate {@code UPDATE} — the predicate of the {@code UPDATE} itself
 * is what decides, the same reasoning {@code JooqLoginBackoffStore} already applies to its own
 * reclaim statement.
 *
 * <p><b>It is a second line of defence, not the serialization point of TOTP verification.</b> Said
 * precisely because the earlier wording of this Javadoc claimed otherwise: reached through {@code
 * VerifyTotpCode}, two concurrent verifications of one account have already serialized at {@code
 * JooqTotpVerificationBackoffStore.claim}, whose {@code INSERT ... ON CONFLICT DO UPDATE ...
 * RETURNING} locks that account's backoff row before either caller reads the credential. This
 * predicate is what holds if that upstream lock is ever removed, reordered, or bypassed, and
 * {@code TotpCounterConcurrencyIT} proves it by calling this method from two concurrent
 * transactions without the backoff claim — the only way the contested case is reachable at all.
 */
public final class JooqTotpCredentialRepository implements TotpCredentialRepository {

    private final DSLContext dsl;

    public JooqTotpCredentialRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insert(InstitutionId institutionId, StaffAccountId accountId,
            String encryptedSecret) {
        dsl.insertInto(IDENTITY_MFA_TOTP_CREDENTIAL)
                .set(IDENTITY_MFA_TOTP_CREDENTIAL.INSTITUTION_ID, institutionId.value())
                .set(IDENTITY_MFA_TOTP_CREDENTIAL.ACCOUNT_ID, accountId.value())
                .set(IDENTITY_MFA_TOTP_CREDENTIAL.ENCRYPTED_SECRET, encryptedSecret)
                .execute();
    }

    @Override
    public Optional<TotpCredential> findByAccountId(InstitutionId institutionId,
            StaffAccountId accountId) {
        return dsl.selectFrom(IDENTITY_MFA_TOTP_CREDENTIAL)
                .where(IDENTITY_MFA_TOTP_CREDENTIAL.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_MFA_TOTP_CREDENTIAL.ACCOUNT_ID.eq(accountId.value()))
                .fetchOptional(record -> new TotpCredential(accountId,
                        record.getEncryptedSecret(), record.getLastAcceptedCounter()));
    }

    @Override
    public boolean acceptCounter(InstitutionId institutionId, StaffAccountId accountId,
            long candidateCounter) {
        int updatedRows = dsl.update(IDENTITY_MFA_TOTP_CREDENTIAL)
                .set(IDENTITY_MFA_TOTP_CREDENTIAL.LAST_ACCEPTED_COUNTER, candidateCounter)
                .where(IDENTITY_MFA_TOTP_CREDENTIAL.INSTITUTION_ID.eq(institutionId.value()))
                .and(IDENTITY_MFA_TOTP_CREDENTIAL.ACCOUNT_ID.eq(accountId.value()))
                .and(IDENTITY_MFA_TOTP_CREDENTIAL.LAST_ACCEPTED_COUNTER.lt(candidateCounter))
                .execute();
        return updatedRows > 0;
    }
}
