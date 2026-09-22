package com.confia.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqAuditLogReader;
import com.confia.shared.security.SecurityContext;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The declared, accepted limit of this control while no external anchor exists (cambio 11;
 * specs/audit-trail/spec.md, "Límite declarado del control mientras no exista ancla externa").
 * Symmetric to {@link AuditChainVerifierIT#aRowAlteredDirectlyWithSuperuserWithoutRecalculatingIsIdentifiedExactly}:
 * that scenario alters a field and leaves the hashes untouched, so the verifier catches it. This
 * one alters a field <em>and</em> recalculates the hash chain from that point forward using the
 * real {@code shared_audit_row_hash(...)} function — the same one the chaining trigger itself
 * calls — so the chain becomes internally consistent again, and asserts the verifier reports it as
 * {@code Intact}, not as a divergence. This is deliberately uncomfortable: it is the only wording
 * that stops anyone from reading this bitácora as tamper-proof before cambio 11's external anchor
 * exists (design.md decision 10; task brief, "el segundo escenario del límite conocido debe
 * recalcular la cadena entera").
 */
class AuditChainKnownLimitIT extends CommittingPostgresIntegrationTest {

    private AuditChainVerifier verifier() {
        return new DefaultAuditChainVerifier(transactionRunner(), new JooqAuditLogReader(dsl),
                new CanonicalAuditRowSerializer());
    }

    @Test
    void manipulationWithFullChainRecalculationIsNotDetectedKnownLimit() throws SQLException {
        InstitutionId institutionId = randomInstitutionId();
        insertRow(institutionId.value(), "actor one");
        long targetId = insertRow(institutionId.value(), "actor two");
        insertRow(institutionId.value(), "actor three");
        insertRow(institutionId.value(), "actor four");

        AuditLogSuperuserTamper.tamperAndRecalculateWholeChainFrom(institutionId.value(), targetId,
                "reason", "manipulated, then the whole chain was recalculated");

        AuditChainVerification result = verifier().verifyChainOf(institutionId);

        assertThat(result)
                .as("a SUPERUSER manipulation that also recalculates row_hash for the tampered row "
                        + "and every row after it, cascading prev_hash forward, makes the chain "
                        + "internally consistent again — the verifier reports integrity, and this "
                        + "green result IS the declared limit, not a bug")
                .isInstanceOf(AuditChainVerification.Intact.class);
    }

    private long insertRow(UUID institutionId, String actorLabel) {
        return transactionRunner().execute(contextOf(institutionId), () -> dsl.fetchOne("""
                insert into shared_audit_log
                    (institution_id, actor_kind, actor_label, request_id, action, entity_type,
                     entity_id, outcome)
                values (?, 'system', ?, ?, 'test.action', 'test_entity', 'entity-1', 'success')
                returning id
                """, institutionId, actorLabel, UUID.randomUUID())
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
