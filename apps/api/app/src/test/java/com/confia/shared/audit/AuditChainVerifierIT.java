package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.security.SecurityContext;
import com.confia.support.AuditChainRows;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The chain verifier's observable contract (design.md decision 11; specs/audit-trail/spec.md,
 * requirements "Contrato observable del verificador de cadena", "Límite declarado del control
 * mientras no exista ancla externa" and "Alcance de la verificación de cadena sin programación
 * recurrente"). Every manipulation here is a real {@code SUPERUSER} write against the real engine
 * (task brief, "la manipulación debe ser real"), through {@link AuditLogSuperuserTamper}, never a
 * simulated divergence.
 *
 * <p><b>Reconciliation note, to report at archive.</b> {@code design.md} §7.1's own trazability
 * table titles {@code audit-trail} "26 escenarios" and lists only three scenarios under
 * "Verificador". The real delta in {@code specs/audit-trail/spec.md} has 27: it adds "Alterar la
 * etiqueta legible del actor rompe la cadena" under the same "Contrato observable del verificador
 * de cadena" requirement, added when the owner closed design.md's open question 1 (2026-09-21,
 * include {@code actor_label}/{@code user_agent}/{@code trace_id} in the hash) after §7.1 was
 * written. {@link #alteringTheActorReadableLabelBreaksTheChain} below is that 27th scenario, in its
 * natural home (same requirement, same test class) — not resolved silently, left here for whoever
 * archives this change to update §7.1 and its header before writing the verification report.
 */
class AuditChainVerifierIT extends CommittingPostgresIntegrationTest {

    private AuditChainVerifier verifier() {
        return new DefaultAuditChainVerifier(transactionRunner(), new JooqAuditLogReader(dsl),
                new CanonicalAuditRowSerializer());
    }

    @Test
    void anIntactChainReportsIntegrityAndIdentifiesNoRow() {
        InstitutionId institutionId = randomInstitutionId();
        insertRow(institutionId.value(), "actor one", "ua-1", "trace-1");
        insertRow(institutionId.value(), "actor two", "ua-2", "trace-2");
        insertRow(institutionId.value(), "actor three", "ua-3", "trace-3");

        AuditChainVerification result = verifier().verifyChainOf(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Intact.class)).satisfies(intact -> {
            assertThat(intact.institutionId()).isEqualTo(institutionId);
            assertThat(intact.verifiedRows()).isEqualTo(3L);
        });
    }

    @Test
    void aRowAlteredDirectlyWithSuperuserWithoutRecalculatingIsIdentifiedExactly() throws SQLException {
        InstitutionId institutionId = randomInstitutionId();
        insertRow(institutionId.value(), "actor one", "ua-1", "trace-1");
        long targetId = insertRow(institutionId.value(), "actor two", "ua-2", "trace-2");
        insertRow(institutionId.value(), "actor three", "ua-3", "trace-3");

        AuditLogSuperuserTamper.tamperFieldWithoutRecalculating(institutionId.value(), targetId,
                "reason", "manipulated, prev_hash and row_hash left untouched");

        AuditChainVerification result = verifier().verifyChainOf(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged -> {
            assertThat(diverged.institutionId()).isEqualTo(institutionId);
            assertThat(diverged.firstDivergentId())
                    .as("the verifier must name the exact (institution_id, id) of the manipulated "
                            + "row, not merely report that a divergence exists")
                    .isEqualTo(targetId);
        });
    }

    @Test
    void theVerifierOfOneInstitutionNeverRecalculatesOrSeesAnothersRows() throws SQLException {
        InstitutionId institutionA = randomInstitutionId();
        InstitutionId institutionB = randomInstitutionId();
        insertRow(institutionA.value(), "actor A1", "ua-a1", "trace-a1");
        long tamperedInB = insertRow(institutionB.value(), "actor B1", "ua-b1", "trace-b1");
        AuditLogSuperuserTamper.tamperFieldWithoutRecalculating(institutionB.value(), tamperedInB,
                "reason", "tampered only in institution B");

        AuditChainVerification result = verifier().verifyChainOf(institutionA);

        assertThat(result)
                .as("a manipulation confined to institution B must never surface in institution "
                        + "A's own verification result")
                .isInstanceOf(AuditChainVerification.Intact.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"actor_label", "user_agent", "trace_id"})
    void alteringTheActorReadableLabelBreaksTheChain(String column) throws SQLException {
        InstitutionId institutionId = randomInstitutionId();
        long targetId = insertRow(institutionId.value(), "original actor", "original-ua",
                "original-trace");

        AuditLogSuperuserTamper.tamperFieldWithoutRecalculating(institutionId.value(), targetId,
                column, "tampered-" + column);

        AuditChainVerification result = verifier().verifyChainOf(institutionId);

        assertThat(result).asInstanceOf(type(AuditChainVerification.Diverged.class)).satisfies(diverged ->
                assertThat(diverged.firstDivergentId())
                        .as("%s enters the hash preimage (design.md §6.2): changing it alone, with "
                                + "no recalculation, must break the chain", column)
                        .isEqualTo(targetId));
    }

    /**
     * Scenarios AT07, AT08, AT09, AT25 and AT26 against the real reader and the real engine (the unit test
     * covers the branches): the window starts at the first row at or after the instant, to the microsecond, keeps
     * the stored previous hash of that row, and does not see an alteration older than the window.
     */
    @Test
    void theWindowOfTheRealChainStartsAtTheInstantAndDoesNotSeeWhatIsOlder() {
        Instant since = Instant.parse("2026-09-01T06:00:00Z");
        InstitutionId institutionId = randomInstitutionId();
        long old = AuditChainRows.insert(institutionId.value(), Instant.parse("2026-07-01T15:00:00Z"));
        AuditChainRows.insert(institutionId.value(), since.minusNanos(1_000));
        AuditChainRows.insert(institutionId.value(), since);
        long tampered = AuditChainRows.insert(institutionId.value(), Instant.parse("2026-09-10T15:00:00Z"));

        assertThat(verifier().verifySince(institutionId, since))
                .asInstanceOf(type(AuditChainVerification.Intact.class))
                .satisfies(intact -> assertThat(intact.verifiedRows()).isEqualTo(2L));

        AuditChainRows.alter(institutionId.value(), old);
        assertThat(verifier().verifySince(institutionId, since)).as("an older alteration is not seen")
                .isInstanceOf(AuditChainVerification.Intact.class);
        assertThat(verifier().verifyChainOf(institutionId))
                .asInstanceOf(type(AuditChainVerification.Diverged.class))
                .satisfies(diverged -> assertThat(diverged.firstDivergentId()).isEqualTo(old));

        AuditChainRows.alter(institutionId.value(), tampered);
        assertThat(verifier().verifySince(institutionId, since))
                .asInstanceOf(type(AuditChainVerification.Diverged.class))
                .satisfies(diverged -> {
                    assertThat(diverged.firstDivergentId()).isEqualTo(tampered);
                    assertThat(diverged.institutionId()).isEqualTo(institutionId);
                });
    }

    @Test
    void aWindowAfterTheLastRowIsEmptyAndAnotherInstitutionsRowsAreNeverInIt() {
        Instant since = Instant.parse("2026-09-01T06:00:00Z");
        InstitutionId institutionId = randomInstitutionId();
        AuditChainRows.insert(institutionId.value(), since.minusSeconds(1));
        AuditChainRows.insert(randomInstitutionId().value(), since.plusSeconds(1));

        assertThat(verifier().verifySince(institutionId, since)).asInstanceOf(type(AuditChainVerification.Empty.class))
                .satisfies(empty -> assertThat(empty.institutionId()).isEqualTo(institutionId));
        assertThat(verifier().verifyChainOf(institutionId)).isInstanceOf(AuditChainVerification.Intact.class);
    }

    private long insertRow(UUID institutionId, String actorLabel, String userAgent, String traceId) {
        return transactionRunner().execute(contextOf(institutionId), () -> dsl.fetchOne("""
                insert into shared_audit_log
                    (institution_id, actor_kind, actor_label, user_agent, request_id, trace_id,
                     action, entity_type, entity_id, outcome)
                values (?, 'system', ?, ?, ?, ?, 'test.action', 'test_entity', 'entity-1', 'success')
                returning id
                """, institutionId, actorLabel, userAgent, UUID.randomUUID(), traceId)
                .get("id", Long.class));
    }

    private static SecurityContext contextOf(UUID institutionId) {
        return new SecurityContext("", "system", institutionId.toString(),
                UUID.randomUUID().toString());
    }

    private static InstitutionId randomInstitutionId() {
        return new InstitutionId(UUID.randomUUID());
    }
}
