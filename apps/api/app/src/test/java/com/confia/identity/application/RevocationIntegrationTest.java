package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.LoginIdentifier;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenFamilyRepository;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.identity.infrastructure.JooqStaffAccountRepository;
import com.confia.kernel.InstitutionId;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared set-up for the revocation integration tests (session-endpoints-cookie-and-csrf tasks.md
 * 2.1): the revocation use cases over the real adapters, and the helpers that let a test seed,
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

    /** An account of {@code institution} with the shared test password. */
    StaffAccountId seedStaffAccountIn(InstitutionId institution, String email) {
        StaffAccountId accountId = new StaffAccountId(UUID.randomUUID());
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
}
