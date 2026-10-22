package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqAccountSessionRevoker;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared set-up for the revocation integration tests (session-endpoints-cookie-and-csrf tasks.md
 * 2.1): the two revocation use cases over the real adapters, and the helpers that let a test seed,
 * issue and read in an institution other than the fixture's own, which is what the cross-institution
 * scenarios (I196 for another institution's refresh token, I208) need. A fresh institution per test
 * is a random identifier: identity tables carry no foreign key to the institution table. Abstract, so
 * the {@code *IT} naming rule does not apply to it.
 */
abstract class RevocationIntegrationTest extends RotationIntegrationTest {

    static final String ACTION_REVOKED = "identity.session.revoked";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    EndCurrentSession endCurrentSession(String instant) {
        return endCurrentSession(institutionId, instant, new JooqAuditLogWriter(dsl));
    }

    EndCurrentSession endCurrentSession(InstitutionId configured, String instant,
            AuditLogWriter auditLogWriter) {
        return new EndCurrentSession(transactionRunner(), () -> configured,
                new JooqRefreshTokenFamilyRepository(dsl), new JooqRefreshTokenRepository(dsl),
                auditLogWriter, fixedClock(instant));
    }

    EndAllSessions endAllSessions(String instant) {
        return endAllSessions(instant, new JooqStaffAccountRepository(dsl),
                new JooqAccountSessionRevoker(dsl), new JooqAuditLogWriter(dsl));
    }

    EndAllSessions endAllSessions(String instant, StaffAccountRepository accounts,
            AccountSessionRevoker revoker, AuditLogWriter auditLogWriter) {
        return new EndAllSessions(transactionRunner(), accounts, revoker, auditLogWriter,
                fixedClock(instant));
    }

    /** The actor an access token of {@code sessionId} would bind, with a made-up token identifier. */
    static AuthenticatedActor actorOf(InstitutionId institution, StaffAccountId account,
            UUID sessionId) {
        return new AuthenticatedActor(account.value(), institution.value(), sessionId,
                UUID.randomUUID(), Set.of(AuthenticationMethod.PASSWORD),
                Instant.parse("2026-10-15T15:00:00Z"), Instant.parse("2026-10-15T15:15:00Z"));
    }

    /** An account of {@code institution} with the shared test password. */
    StaffAccountId seedStaffAccountIn(InstitutionId institution, String email) {
        return seedStaffAccountIn(institution, new StaffAccountId(UUID.randomUUID()), email);
    }

    /**
     * The same, with the identifier of the account chosen by the test: the key of an account is the
     * pair institution and identifier, so two institutions can hold the same identifier.
     */
    StaffAccountId seedStaffAccountIn(InstitutionId institution, StaffAccountId accountId,
            String email) {
        String hash = HASHER.hash(PlainPassword.of(CURRENT_PASSWORD)).value();
        transactionRunner().execute(contextOf(institution), () -> dsl.execute("""
                insert into identity_staff_account
                    (institution_id, id, email, password_hash, mfa_required)
                values (?, ?, ?, ?, false)
                """, institution.value(), accountId.value(), LoginIdentifier.of(email).value(),
                hash));
        return accountId;
    }

    /** A real issuance in {@code institution}: family, open refresh token and access token. */
    IssuedSession issueIn(InstitutionId institution, String instant, StaffAccountId account) {
        return issueSession(instant).execute(contextOf(institution),
                new IssueSessionCommand(account, false, AGENT_ONE, null));
    }

    /** A real rotation in {@code institution}, with the first test agent. */
    RefreshOutcome rotateIn(InstitutionId institution, String instant, String presentedToken) {
        return rotation(fixedClock(instant), new JooqAuditLogWriter(dsl), institution)
                .execute(contextOf(institution),
                        new RotateRefreshTokenCommand(presentedToken, AGENT_ONE));
    }

    Record familyIn(InstitutionId institution, UUID familyId) {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetchSingle("""
                select account_id, last_used_at, revoked_at, revoked_reason
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, institution.value(), familyId));
    }

    List<Record> familiesIn(InstitutionId institution, StaffAccountId account) {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetch("""
                select id, revoked_at, revoked_reason from identity_refresh_token_family
                 where institution_id = ? and account_id = ? order by id
                """, institution.value(), account.value()));
    }

    /** Tokens of the account's families that can still be presented: open, in a family not revoked. */
    long liveTokensIn(InstitutionId institution, StaffAccountId account) {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetchSingle("""
                select count(*) from identity_refresh_token t
                  join identity_refresh_token_family f
                    on f.institution_id = t.institution_id and f.id = t.family_id
                 where t.institution_id = ? and f.account_id = ?
                   and t.consumed_at is null and f.revoked_at is null
                """, institution.value(), account.value()).get(0, Long.class));
    }

    long encryptedSuccessorsIn(InstitutionId institution, StaffAccountId account) {
        return transactionRunner().execute(contextOf(institution), () -> dsl.fetchSingle("""
                select count(*) from identity_refresh_token t
                  join identity_refresh_token_family f
                    on f.institution_id = t.institution_id and f.id = t.family_id
                 where t.institution_id = ? and f.account_id = ?
                   and t.successor_token_ciphertext is not null
                """, institution.value(), account.value()).get(0, Long.class));
    }

    List<AuditRowSnapshot> auditRowsIn(InstitutionId institution, String action) {
        return transactionRunner().execute(contextOf(institution),
                () -> new JooqAuditLogReader(dsl).pageOf(institution, 0, 500)).stream()
                .filter(row -> row.action().equals(action)).toList();
    }

    /**
     * The document of a closing entry: the reason {@code logout} and the number of families, and
     * nothing else. The audit column is {@code jsonb}, so the text comes back normalized and the
     * document is read, not compared as text.
     */
    static void assertLogoutDocument(AuditRowSnapshot row, int families) {
        JsonNode after = JSON.readTree(row.afterValue());
        assertThat(after.get("reason").asString())
                .isEqualTo("logout");
        assertThat(after.get("families").asInt())
                .isEqualTo(families);
        assertThat(after.size())
                .as("nothing but the reason and the count").isEqualTo(2);
    }

    /** What two transactions leave: both results and whether the second really waited for the first. */
    record Race<A, B>(A holder, B waiter, boolean waiterWaited) {
    }

    /**
     * Runs {@code holder}, which must count {@code locked} down once it owns the lock of the family
     * and then wait for {@code release}; starts {@code waiter} once that happened; goes on when the
     * database reports a backend waiting for a row lock on the family (a condition with a bound, never
     * a pause); and releases the holder.
     */
    <A, B> Race<A, B> race(CountDownLatch locked, CountDownLatch release, Callable<A> holder,
            Callable<B> waiter) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<A> first = pool.submit(holder);
                assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
                Future<B> second = pool.submit(waiter);
                boolean waited = holdsWithin(20_000, () -> second.isDone()
                        || someBackendWaitsForALockOn("identity_refresh_token_family"))
                        && !second.isDone();
                release.countDown();
                return new Race<>(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS),
                        waited);
            } finally {
                release.countDown();
            }
        }
    }
}
