package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.IdentifierFingerprint;
import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.StoredPasswordHash;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.infrastructure.Argon2Pepper;
import com.confia.identity.infrastructure.Argon2Profile;
import com.confia.identity.infrastructure.BouncyCastleArgon2PasswordHasher;
import com.confia.identity.infrastructure.HmacLoginIdentifierFingerprinter;
import com.confia.identity.infrastructure.JooqLoginBackoffStore;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The atomicity half of design.md decision 9: the failed-attempt counter, the delay it produces
 * and the audit entry it writes live inside a single transaction, and none of the three survives
 * unless that transaction commits (specs/identity/spec.md, requirement "Estado del retroceso
 * persistido en PostgreSQL...", escenario "El efecto y su asiento de auditoría se confirman o
 * revierten juntos"). This is the debt PR C3a's own package-private {@code runWithinTransaction}
 * left unproven: a unit test with doubles cannot demonstrate that three real writes share one real
 * transaction boundary — only {@link CommittingPostgresIntegrationTest}, with real commits, can.
 *
 * <p>Deterministic, never a race: a caller-thrown exception right after {@link
 * AuthenticateWithPassword#runWithinTransaction} returns is what forces the rollback, not luck.
 */
class LoginBackoffAtomicityIT extends CommittingPostgresIntegrationTest {

    private static final Argon2Pepper PEPPER =
            Argon2Pepper.fromBase64(Base64.getEncoder().encodeToString(new byte[32]));
    private static final BouncyCastleArgon2PasswordHasher HASHER =
            new BouncyCastleArgon2PasswordHasher(Argon2Profile.floor(), PEPPER);
    private static final HmacLoginIdentifierFingerprinter FINGERPRINTER =
            new HmacLoginIdentifierFingerprinter(PEPPER);

    @Test
    void aDeterministicFailureBeforeCommitLeavesNeitherTheCounterNorTheAuditEntry() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("nadie.registrado@colegio.edu.hn");
        IdentifierFingerprint fingerprint = FINGERPRINTER.fingerprintOf(identifier);
        AuthenticateWithPassword useCase = useCase(institutionId, "2026-03-10T13:00:00Z");
        SecurityContext context = contextOf(institutionId);

        assertThatThrownBy(() -> transactionRunner().execute(context, () -> {
            useCase.runWithinTransaction(institutionId, context.requestId(),
                    new AuthenticationCommand(identifier.value(), "cualquiera"));
            throw new IllegalStateException(
                    "deterministic failure, rolls the counter and the audit entries back with it");
        }))
                .as("the caller's own thrown exception, never a serialization/deadlock SQLState, "
                        + "so TransactionRunner must never retry this")
                .isInstanceOf(IllegalStateException.class);

        assertThat(countBackoffRows(institutionId, fingerprint))
                .as("the reclaim's own INSERT never survives the rollback: no row at all, not even "
                        + "the initial (0, now) one")
                .isZero();
        assertThat(countAuditRows(institutionId, fingerprint.value()))
                .as("neither the login.failed row nor the backoff_applied row (there is none at "
                        + "ordinal 1 anyway) survives the rollback")
                .isZero();
    }

    /**
     * The positive control (the same discipline {@code IdentityRowSecurityIT}'s own negative
     * control follows, in reverse): if this did not commit, the assertion above would pass for the
     * wrong reason — every transaction always rolls back in this environment, say — rather than
     * because the deterministic failure specifically caused it.
     */
    @Test
    void aCommittedAttemptDoesLeaveBothTheCounterAndTheAuditEntries() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("nadie.registrado@colegio.edu.hn");
        IdentifierFingerprint fingerprint = FINGERPRINTER.fingerprintOf(identifier);
        AuthenticateWithPassword useCase = useCase(institutionId, "2026-03-10T13:00:00Z");

        useCase.execute(contextOf(institutionId),
                new AuthenticationCommand(identifier.value(), "cualquiera"));

        assertThat(countBackoffRows(institutionId, fingerprint)).isEqualTo(1);
        assertThat(countAuditRows(institutionId, fingerprint.value())).isEqualTo(1);
    }

    /**
     * The same rollback, on the branch the two tests above never reach. Both of them present an
     * identifier with no account, so the use case takes the decoy path: the repository returns
     * empty, no stored hash is read, and {@code actorId} stays null. A real account exercises the
     * other branch — a row read from {@code identity_staff_account}, a verification against its
     * actual hash, a non-null actor — and nothing here demonstrated that a deterministic failure on
     * <em>that</em> path also leaves no trace.
     *
     * <p>Found by the pre-merge security audit as a coverage gap rather than a mechanism one: the
     * write is the same shape on both branches, same rows and same lock order, so the guarantee was
     * argued from symmetry. Decision 9 states atomicity as a property of the single transaction
     * without distinguishing the two, and a property worth stating is worth demonstrating.
     */
    @Test
    void aDeterministicFailureAlsoLeavesNoTraceWhenTheAccountReallyExists() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        LoginIdentifier identifier = LoginIdentifier.of("carlos.ramirez@colegio.edu.hn");
        IdentifierFingerprint fingerprint = FINGERPRINTER.fingerprintOf(identifier);
        seedStaffAccount(institutionId, identifier, "PasswordReal#2026");
        AuthenticateWithPassword useCase = useCase(institutionId, "2026-03-10T13:00:00Z");
        SecurityContext context = contextOf(institutionId);

        assertThatThrownBy(() -> transactionRunner().execute(context, () -> {
            useCase.runWithinTransaction(institutionId, context.requestId(),
                    new AuthenticationCommand(identifier.value(), "PasswordEquivocada#2026"));
            throw new IllegalStateException(
                    "deterministic failure on the real-account branch, after the three effects");
        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(countBackoffRows(institutionId, fingerprint))
                .as("the account exists and its stored hash was read, yet the backoff row must not "
                        + "survive the rollback either")
                .isZero();
        assertThat(countAuditRows(institutionId, fingerprint.value()))
                .as("nor may the login.failed entry survive, even with a non-null actor id on it")
                .isZero();
    }

    private void seedStaffAccount(InstitutionId institutionId, LoginIdentifier identifier,
            String password) {
        StoredPasswordHash hash = HASHER.hash(PlainPassword.of(password));
        transactionRunner().execute(contextOf(institutionId), () -> {
            dsl.execute("""
                    insert into identity_staff_account
                        (institution_id, id, email, password_hash, mfa_required)
                    values (?, ?, ?, ?, false)
                    """, institutionId.value(), UUID.randomUUID(), identifier.value(), hash.value());
            return null;
        });
    }

    private AuthenticateWithPassword useCase(InstitutionId institutionId, String instant) {
        return new AuthenticateWithPassword(transactionRunner(), () -> institutionId,
                new JooqStaffAccountRepository(dsl), new JooqLoginBackoffStore(dsl), HASHER,
                FINGERPRINTER, new JooqAuditLogWriter(dsl), fixedClock(instant));
    }

    private long countBackoffRows(InstitutionId institutionId, IdentifierFingerprint fingerprint) {
        return transactionRunner().execute(contextOf(institutionId),
                () -> dsl.fetchOne("""
                        select count(*) as c from identity_login_backoff
                         where institution_id = ? and identifier_hash = ?
                        """, institutionId.value(), fingerprint.value())
                        .get("c", Number.class).longValue());
    }

    private long countAuditRows(InstitutionId institutionId, String entityId) {
        return transactionRunner().execute(contextOf(institutionId),
                () -> dsl.fetchOne("""
                        select count(*) as c from shared_audit_log
                         where institution_id = ? and entity_id = ?
                        """, institutionId.value(), entityId)
                        .get("c", Number.class).longValue());
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
