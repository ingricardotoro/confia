package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.identity.domain.RefreshTokenHash;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.shared.audit.AuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticatedActor;
import com.confia.shared.security.AuthenticationMethod;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issuing a session family (session-tokens-and-web-layer design.md decision 9, scenarios I87 to I90,
 * I119, I120 and I126; the second-factor and audit-origin scenarios are in the next slice, of
 * web-edge), against a real PostgreSQL and the real adapters. Only tests invoke the issuance in this
 * change. Everything stored is read back with raw SQL, so what is asserted is what a database
 * administrator would see.
 */
class IssueSessionIT extends SessionIssuanceIntegrationTest {

    private static final String EIGHT = "2026-10-12T08:00:00Z";
    private static final String ACTION_STARTED = "identity.session.started";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Scenarios I87 and I90, and the access token the session hands out after the commit. */
    @Test
    void aSessionIsIssuedWithItsFamilyItsTokenAndItsEntryInOneTransaction() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        IssuedSession session = issue(EIGHT, ana);

        Record family = familyOf(session.sessionId());
        assertThat(family.get("account_id", UUID.class)).isEqualTo(ana.value());
        assertThat(instant(family, "created_at")).isEqualTo(Instant.parse(EIGHT));
        assertThat(instant(family, "last_used_at")).isEqualTo(Instant.parse(EIGHT));
        assertThat(instant(family, "absolute_expires_at"))
                .isEqualTo(Instant.parse("2026-10-12T20:00:00Z"));
        assertThat(family.get("revoked_at")).isNull();
        assertThat(family.get("second_factor_verified", Boolean.class)).isFalse();
        Record token = onlyTokenOf(session.sessionId());
        assertThat(instant(token, "issued_at")).isEqualTo(Instant.parse(EIGHT));
        assertThat(instant(token, "expires_at")).isEqualTo(Instant.parse("2026-10-12T16:00:00Z"));
        assertThat(token.get("consumed_at")).isNull();
        assertThat(session.refreshExpiresAt()).isEqualTo(Instant.parse("2026-10-12T16:00:00Z"));
        String delivered = session.refreshToken().value();
        assertThat(delivered).hasSize(43).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(Base64.getUrlDecoder().decode(delivered)).hasSize(32);

        AuthenticatedActor actor = verifierAt(EIGHT).verifyAccess(session.accessToken().compact());
        assertThat(actor.sessionId()).isEqualTo(session.sessionId());
        assertThat(actor.accountId()).isEqualTo(ana.value());
        assertThat(actor.institutionId()).isEqualTo(institutionId.value());
        assertThat(actor.methods()).containsExactlyInAnyOrder(AuthenticationMethod.PASSWORD);
        assertThat(session.accessExpiresAt()).isEqualTo(Instant.parse("2026-10-12T08:10:00Z"));

        assertThat(auditRows(ACTION_STARTED)).singleElement().satisfies(row -> {
            assertThat(row.entityType()).isEqualTo("identity.refresh_token_family");
            assertThat(row.entityId()).isEqualTo(session.sessionId().toString());
            assertThat(row.actorId()).isEqualTo(ana.value());
            assertThat(row.actorKind()).isEqualTo("staff");
            assertThat(row.outcome()).isEqualTo("success");
            assertThat(JSON.readTree(row.afterValue()).get("secondFactor").asBoolean()).isFalse();
        });
    }

    /** Scenario I88 (ADR-0005, test 5): the stored value is the SHA-256, never the delivered text. */
    @Test
    void theStoredValueIsTheSha256OfTheDeliveredTokenAndNoColumnHoldsTheToken() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        IssuedSession first = issue(EIGHT, ana);
        IssuedSession second = issue("2026-10-12T08:01:00Z", ana);

        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        String stored = onlyTokenOf(first.sessionId()).get("token_hash", String.class);
        assertThat(stored).isNotEqualTo(first.refreshToken().value());
        // The database computes the SHA-256 itself, so the expectation owes nothing to the code
        // under test.
        assertThat(transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')
                  from identity_refresh_token where institution_id = ? and family_id = ?
                """, first.refreshToken().value(), institutionId.value(), first.sessionId())
                .get(0, Boolean.class)))
                .as("token_hash is the SHA-256 of the delivered text").isTrue();
        for (IssuedSession session : List.of(first, second)) {
            assertThat(everythingStoredAboutSessions())
                    .as("no column of any row holds a delivered refresh token or access token")
                    .doesNotContain(session.refreshToken().value())
                    .doesNotContain(session.accessToken().compact());
        }
    }

    /** Scenario I90: the entry carries no secret, not even the hash. */
    @Test
    void theEntryContainsNeitherTheTokenNorItsHash() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, true);

        IssuedSession session = issueSession(EIGHT).execute(context(),
                commandFrom(ana, true, "agente", "203.0.113.9"));

        String hash = RefreshTokenHash.of(session.refreshToken()).value();
        assertThat(auditRows(ACTION_STARTED)).singleElement().satisfies(row -> {
            assertThat(row.toString()).doesNotContain(session.refreshToken().value())
                    .doesNotContain(session.accessToken().compact()).doesNotContain(hash);
            assertThat(row.entityId()).doesNotContain(hash);
            assertThat(JSON.readTree(row.afterValue()).size()).isEqualTo(1);
        });
    }

    /** Scenario I89: if the unit does not commit, nothing of it exists. */
    @Test
    void aFailureAfterTheFamilyAndTheTokenAreWrittenLeavesNothingBehind() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        JooqAuditLogWriter real = new JooqAuditLogWriter(dsl);
        AuditLogWriter failing = entry -> {
            real.append(entry);
            throw new IllegalStateException("the process failed before the commit");
        };

        assertThatThrownBy(() -> issueSession(EIGHT, failing, new JooqRefreshTokenRepository(dsl))
                .execute(context(), commandFrom(ana, false, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the process failed before the commit");

        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(count("identity_refresh_token")).isZero();
        assertThat(auditRows(ACTION_STARTED)).isEmpty();
    }

    @Test
    void aFailureWhileWritingTheTokenTakesTheFamilyWithIt() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        RefreshTokenRepository failing = (institution, row) -> {
            throw new IllegalStateException("the token could not be written");
        };

        assertThatThrownBy(() -> issueSession(EIGHT, new JooqAuditLogWriter(dsl), failing)
                .execute(context(), commandFrom(ana, false, null, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(count("identity_refresh_token")).isZero();
    }

    @Test
    void anAccountThatDoesNotExistIsAProgrammingErrorAndWritesNothing() {
        StaffAccountId nobody = new StaffAccountId(UUID.randomUUID());

        assertThatThrownBy(() -> issue(EIGHT, nobody)).isInstanceOf(IllegalStateException.class);

        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(auditRows(ACTION_STARTED)).isEmpty();
    }

    /** Scenario I119: a digest of the agent and the first address, never the agent's text. */
    @Test
    void onlyTheFingerprintAndTheFirstAddressAreStored() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        String agent = "Mozilla/5.0 (agente-prueba)";

        IssuedSession session = issueSession(EIGHT).execute(context(),
                commandFrom(ana, false, agent, "203.0.113.9"));

        Record family = transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select host(ip_first_seen) as ip, user_agent_fingerprint
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, institutionId.value(), session.sessionId()));
        assertThat(family.get("ip", String.class)).isEqualTo("203.0.113.9");
        assertThat(family.get("user_agent_fingerprint", String.class)).matches("^[0-9a-f]{64}$");
        assertThat(everythingStoredAboutSessions()).doesNotContain("agente-prueba");
    }

    @Test
    void anIpv6AddressIsStoredAsAnAddress() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        IssuedSession session = issueSession(EIGHT).execute(context(),
                commandFrom(ana, false, "agente", "2001:db8::1"));

        assertThat(transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select host(ip_first_seen) as ip from identity_refresh_token_family
                 where institution_id = ? and id = ?
                """, institutionId.value(), session.sessionId()).get("ip", String.class)))
                .isEqualTo("2001:db8::1");
    }

    /** Scenario I120: no request, no agent, no address. */
    @Test
    void anIssuanceWithoutOriginIsAcceptedWithANullAddressAndTheFingerprintOfNoAgent() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);

        IssuedSession session = issue(EIGHT, ana);

        Record family = transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select ip_first_seen, user_agent_fingerprint from identity_refresh_token_family
                 where institution_id = ? and id = ?
                """, institutionId.value(), session.sessionId()));
        assertThat(family.get("ip_first_seen")).isNull();
        assertThat(family.get("user_agent_fingerprint", String.class))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    /** Scenario I126: a session opened after a reset is not the reset's to revoke. */
    @Test
    void aSessionStartedAfterAResetIsNotAffectedByIt() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        String token = issueTokenAt(ana, "2026-10-12T09:50:00Z").value();
        resetAt(token, "Lempira y maíz 2026", new SecondFactorProof.None(), "2026-10-12T10:00:00Z");

        IssuedSession session = issue("2026-10-12T10:05:00Z", ana);

        assertThat(familyOf(session.sessionId()).get("revoked_at")).isNull();
        assertThat(familyOf(session.sessionId()).get("revoked_reason")).isNull();
    }

    // ---------------------------------------------------------------------------------------------

    private Record familyOf(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select account_id, created_at, last_used_at, absolute_expires_at, revoked_at,
                       revoked_reason, second_factor_verified
                  from identity_refresh_token_family where institution_id = ? and id = ?
                """, institutionId.value(), familyId));
    }

    private Record onlyTokenOf(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select token_hash, issued_at, expires_at, consumed_at
                  from identity_refresh_token where institution_id = ? and family_id = ?
                """, institutionId.value(), familyId));
    }

    private long count(String table) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle(
                "select count(*) from " + table + " where institution_id = ?",
                institutionId.value()).get(0, Long.class));
    }

    /** Every row of both tables as text, the way a dump of the database would show them. */
    private String everythingStoredAboutSessions() {
        return transactionRunner().execute(context(), () -> dsl.fetch("""
                select row_to_json(f)::text from identity_refresh_token_family f
                union all
                select row_to_json(t)::text from identity_refresh_token t
                union all
                select row_to_json(a)::text from shared_audit_log a
                """).formatCSV());
    }

    private static Instant instant(Record record, String column) {
        return record.get(column, OffsetDateTime.class).toInstant();
    }
}
