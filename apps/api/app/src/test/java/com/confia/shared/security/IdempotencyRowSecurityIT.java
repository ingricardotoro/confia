package com.confia.shared.security;

import static confia.generated.jooq.tables.SharedIdempotencyKey.SHARED_IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Row-level security on {@code shared_idempotency_key}, by institution
 * (specs/build-integrity/spec.md, requirement "Tabla {@code shared_idempotency_key}, clave primaria
 * natural y seguridad de fila forzada"; {@code docs/03-seguridad.md} §6.2 and §6.4 point 1).
 *
 * <p><b>Why this class exists at all.</b> Until the pre-merge review of change 6 it did not, and
 * neither did the scenario it implements. The requirement's two original scenarios both asserted
 * that the generic schema gates accept the table — that the policy is <em>declared</em>, that
 * {@code FORCE ROW LEVEL SECURITY} is on, that the composite key counts as the discriminator. None
 * of them made two institutions and watched one fail to reach the other's row.
 *
 * <p>That distinction is the whole point of {@code CLAUDE.md}'s rule that every row-level policy
 * needs an integration test proving one user cannot read another's data. A catalogue assertion
 * proves the policy exists; only this proves it works. {@code AuditLogRowSecurityIT} is the
 * precedent, written for the same reason on the audit log.
 */
class IdempotencyRowSecurityIT extends CommittingPostgresIntegrationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String ENDPOINT = "/api/v1/payments";

    /**
     * The same {@code (endpoint, idempotency_key)} pair deliberately, differing only by
     * institution: if the policy were missing, the primary key alone would not keep them apart, and
     * institution B would lock institution A's row rather than find nothing.
     */
    @Test
    void oneInstitutionNeitherReadsNorLocksAnothersIdempotencyKey() {
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
        IdempotencyKey sharedKeyValue = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());

        executeOnce(institutionA, sharedKeyValue);

        Optional<IdempotencyRecord> seenByB = transactionRunner().execute(contextOf(institutionB),
                () -> store().lockExisting(institutionB, sharedKeyValue));

        assertThat(seenByB)
                .as("institution B must find nothing for a key institution A owns, although the row "
                        + "physically exists: the row policy, not the primary key, is what keeps "
                        + "them apart")
                .isEmpty();

        long visibleToB = transactionRunner().execute(contextOf(institutionB),
                () -> (long) dsl.fetchCount(dsl.selectFrom(SHARED_IDEMPOTENCY_KEY)));

        assertThat(visibleToB)
                .as("a plain count under institution B's context must reach zero rows, so the "
                        + "policy filters reads and not merely the lock helper's own predicate")
                .isZero();

        long visibleToA = transactionRunner().execute(contextOf(institutionA),
                () -> (long) dsl.fetchCount(dsl.selectFrom(SHARED_IDEMPOTENCY_KEY)));

        assertThat(visibleToA)
                .as("institution A must still see its own row: a policy that hid everything from "
                        + "everyone would satisfy the assertion above for the wrong reason")
                .isEqualTo(1L);
    }

    /**
     * Both institutions using the very same key value, each executing once. Without the policy the
     * second would collide on the primary key; with it, each owns its own row and its own effect.
     */
    @Test
    void twoInstitutionsMayHoldTheSameKeyValueIndependently() {
        InstitutionId institutionA = new InstitutionId(UUID.randomUUID());
        InstitutionId institutionB = new InstitutionId(UUID.randomUUID());
        IdempotencyKey sharedKeyValue = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());

        IdempotentOutcome outcomeA = executeOnce(institutionA, sharedKeyValue);
        IdempotentOutcome outcomeB = executeOnce(institutionB, sharedKeyValue);

        assertThat(outcomeA)
                .as("institution A's request must execute, not replay")
                .isInstanceOf(IdempotentOutcome.Executed.class);
        assertThat(outcomeB)
                .as("institution B's identical key value must also execute: the key is scoped to "
                        + "its institution, so this is a new key and never A's replay")
                .isInstanceOf(IdempotentOutcome.Executed.class);

        assertThat(transactionRunner().execute(contextOf(institutionA),
                () -> store().lockExisting(institutionA, sharedKeyValue)))
                .as("each institution keeps its own row for the same key value").isPresent();
        assertThat(transactionRunner().execute(contextOf(institutionB),
                () -> store().lockExisting(institutionB, sharedKeyValue))).isPresent();
    }

    private IdempotentOutcome executeOnce(InstitutionId institutionId, IdempotencyKey key) {
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"10.0000\"}");
        return new IdempotentExecutor(transactionRunner(), store(), new RequestPayloadHasher(),
                Clock.systemUTC(), dataSource())
                .execute(contextOf(institutionId), key, payload,
                        () -> new IdempotentResponse(200, JSON_MAPPER.readTree("{\"ok\":true}")));
    }

    private IdempotencyRecordStore store() {
        return new JooqIdempotencyRecordStore(dsl);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
