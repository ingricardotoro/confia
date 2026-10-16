package com.confia.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.domain.AuthenticationResult;
import com.confia.identity.domain.StaffAccountId;
import com.confia.identity.infrastructure.JooqRefreshTokenRepository;
import com.confia.shared.audit.AuditRowSnapshot;
import com.confia.shared.audit.RequestOriginAuditLogWriter;
import com.confia.shared.infrastructure.JooqAuditLogWriter;
import com.confia.shared.security.AuthenticationMethod;
import com.confia.shared.security.ClientAddress;
import com.confia.shared.security.RequestOrigin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jooq.Record;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The second-factor and audit-origin scenarios of the session issuance (session-tokens-and-web-layer
 * design.md decision 9): the two outcomes of an account with MFA that open no session, the
 * second-factor flag reaching the family, the entry and the {@code amr}, and the three
 * "origin reaches the audit trail" scenarios of web-edge, with fifty concurrent requests. Against a
 * real PostgreSQL and the real adapters, like {@link IssueSessionIT}.
 */
class IssueSessionOriginAndSecondFactorIT extends SessionIssuanceIntegrationTest {

    private static final String EIGHT = "2026-10-12T08:00:00Z";
    private static final String ACTION_STARTED = "identity.session.started";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The second-factor flag reaches the family, the entry and the {@code amr} of the access token. */
    @Test
    void aSessionOpenedAfterTheSecondFactorRecordsItEverywhere() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, true);

        IssuedSession session = issueSession(EIGHT).execute(context(),
                commandFrom(ana, true, null, null));

        assertThat(familyOf(session.sessionId()).get("second_factor_verified", Boolean.class))
                .isTrue();
        assertThat(verifierAt(EIGHT).verifyAccess(session.accessToken().compact()).methods())
                .containsExactlyInAnyOrder(AuthenticationMethod.PASSWORD,
                        AuthenticationMethod.ONE_TIME_PASSWORD);
        assertThat(JSON.readTree(auditRows(ACTION_STARTED).get(0).afterValue())
                .get("secondFactor").asBoolean()).isTrue();
    }

    /** The two outcomes of an account with MFA: neither of them opens a session. */
    @Test
    void anAccountWithMfaThatHasNotCompletedItsSecondFactorGetsNoFamily() {
        seedStaffAccount("sofia.mejia@colegio.edu.hn", CURRENT_PASSWORD, true);
        enrollTotp(seedStaffAccount("jorge.aguilar@colegio.edu.hn", CURRENT_PASSWORD, true));

        assertThat(loginAt("jorge.aguilar@colegio.edu.hn", CURRENT_PASSWORD, "2026-10-12T08:00:00Z"))
                .isInstanceOf(AuthenticationResult.SecondFactorRequired.class);
        assertThat(loginAt("sofia.mejia@colegio.edu.hn", CURRENT_PASSWORD, "2026-10-12T08:00:01Z"))
                .isInstanceOf(AuthenticationResult.SecondFactorEnrollmentRequired.class);

        assertThat(count("identity_refresh_token_family")).isZero();
        assertThat(count("identity_refresh_token")).isZero();
        assertThat(auditRows(ACTION_STARTED)).isEmpty();
    }

    /** The first origin scenario of web-edge: an entry written during a request carries its origin. */
    @Test
    void theEntryOfAnIssuanceDuringARequestCarriesItsAddressAndAgent() throws Exception {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssueSession useCase = issueSession(EIGHT,
                new RequestOriginAuditLogWriter(new JooqAuditLogWriter(dsl)),
                new JooqRefreshTokenRepository(dsl));
        RequestOrigin origin = new RequestOrigin(UUID.randomUUID(),
                ClientAddress.parseLiteral("203.0.113.9"), "agente-prueba");

        ScopedValue.where(RequestOrigin.CURRENT, origin).call(() ->
                useCase.execute(context(), commandFrom(ana, false, "agente-prueba", "203.0.113.9")));

        assertThat(auditRows(ACTION_STARTED)).singleElement().satisfies(row -> {
            assertThat(row.sourceIp()).isEqualTo("203.0.113.9");
            assertThat(row.userAgent()).isEqualTo("agente-prueba");
        });
    }

    /** The second origin scenario: outside a request, both fields stay null. */
    @Test
    void theEntryOfAnIssuanceOutsideARequestKeepsBothFieldsNull() {
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssueSession useCase = issueSession(EIGHT,
                new RequestOriginAuditLogWriter(new JooqAuditLogWriter(dsl)),
                new JooqRefreshTokenRepository(dsl));

        useCase.execute(context(), commandFrom(ana, false, null, null));

        assertThat(auditRows(ACTION_STARTED)).singleElement().satisfies(row -> {
            assertThat(row.sourceIp()).isNull();
            assertThat(row.userAgent()).isNull();
        });
    }

    /** The third origin scenario: fifty concurrent requests, each entry with its own origin. */
    @Test
    void fiftyConcurrentIssuancesEachWriteTheirOwnAddressAndAgentWithoutCrossTalk()
            throws Exception {
        int requests = 50;
        StaffAccountId ana = seedStaffAccount(ANA, CURRENT_PASSWORD, false);
        IssueSession useCase = issueSession(EIGHT,
                new RequestOriginAuditLogWriter(new JooqAuditLogWriter(dsl)),
                new JooqRefreshTokenRepository(dsl));
        CyclicBarrier allReady = new CyclicBarrier(requests);
        Map<UUID, Integer> requestOfSession = new ConcurrentHashMap<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(requests)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    allReady.await(30, TimeUnit.SECONDS);
                    String address = "198.51.100." + (n + 1);
                    RequestOrigin origin = new RequestOrigin(UUID.randomUUID(),
                            ClientAddress.parseLiteral(address), "agent-" + n);
                    IssuedSession session = ScopedValue.where(RequestOrigin.CURRENT, origin)
                            .call(() -> useCase.execute(context(),
                                    commandFrom(ana, false, "agent-" + n, address)));
                    requestOfSession.put(session.sessionId(), n);
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        }

        List<AuditRowSnapshot> rows = auditRows(ACTION_STARTED);
        assertThat(rows).hasSize(requests);
        for (AuditRowSnapshot row : rows) {
            int n = requestOfSession.get(UUID.fromString(row.entityId()));
            assertThat(row.sourceIp()).isEqualTo("198.51.100." + (n + 1));
            assertThat(row.userAgent()).isEqualTo("agent-" + n);
        }
        assertThat(rows.stream().map(AuditRowSnapshot::sourceIp).distinct()).hasSize(requests);
    }


    private Record familyOf(UUID familyId) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle("""
                select second_factor_verified from identity_refresh_token_family
                 where institution_id = ? and id = ?
                """, institutionId.value(), familyId));
    }

    private long count(String table) {
        return transactionRunner().execute(context(), () -> dsl.fetchSingle(
                "select count(*) from " + table + " where institution_id = ?",
                institutionId.value()).get(0, Long.class));
    }
}
