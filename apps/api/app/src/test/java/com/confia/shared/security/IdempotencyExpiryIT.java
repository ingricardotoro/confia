package com.confia.shared.security;

import static confia.generated.jooq.tables.SharedIdempotencyKey.SHARED_IDEMPOTENCY_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reuse of a caducated key by updating the existing row, never {@code DELETE}+{@code INSERT}
 * (design.md, decision 7; specs/build-integrity/spec.md, requirements "Reutilización de una clave
 * caducada por actualización de la fila existente" and "Ausencia de purga física de claves
 * caducadas..."). PR C3.
 *
 * <p>A {@link Clock} fixed by constructor decides caducity, never a real wait nor a hand-written
 * {@code expires_at} in the past (design.md, decision 7, "La caducidad la decide el reloj de
 * Java..."): the first {@link IdempotentExecutor} uses a clock fixed at {@code T0} to complete a
 * marker whose {@code expires_at} is {@code T0 + 24h}; the second uses a clock fixed at {@code T1 =
 * T0 + 25h}, one hour past that expiry, to reuse the same key.
 */
class IdempotencyExpiryIT extends CommittingPostgresIntegrationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String ENDPOINT = "/api/v1/payments";
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = T0.plus(Duration.ofHours(25));

    private IdempotencyRecordStore store() {
        return new JooqIdempotencyRecordStore(dsl);
    }

    private IdempotentExecutor executorAt(Instant instant) {
        return new IdempotentExecutor(transactionRunner(), store(), new RequestPayloadHasher(),
                Clock.fixed(instant, ZoneOffset.UTC), dataSource());
    }

    @Test
    void anExpiredKeyIsReusedByUpdatingTheExistingRowNeverInsertingANewOne() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"100.0000\"}");
        IdempotentResponse firstResponse =
                new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"a\"}"));

        IdempotentOutcome first = executorAt(T0).execute(contextOf(institutionId), key, payload,
                () -> firstResponse);
        assertThat(first).isInstanceOf(IdempotentOutcome.Executed.class);

        IdempotencyRecord beforeReuse = readRecord(institutionId, key);
        assertThat(beforeReuse.status()).isEqualTo("COMPLETED");
        assertThat(beforeReuse.expiresAt())
                .as("before the new request arrives, the row must still exist with an "
                        + "already-vencido expires_at — no purge job is deployed in this change "
                        + "(brecha con destino: cambio 9)")
                .isBefore(T1);
        Instant originalCreatedAt = beforeReuse.createdAt();

        int[] invocations = new int[1];
        IdempotentResponse secondResponse =
                new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"b\"}"));

        IdempotentOutcome second = executorAt(T1).execute(contextOf(institutionId), key, payload,
                () -> {
                    invocations[0]++;
                    return secondResponse;
                });

        assertThat(second)
                .as("an expired key must be treated exactly as if it were new: the use case runs "
                        + "again")
                .isInstanceOf(IdempotentOutcome.Executed.class);
        assertThat(second.response()).isEqualTo(secondResponse);
        assertThat(invocations[0]).isEqualTo(1);

        assertThat(countRowsForPrimaryKey(institutionId, key))
                .as("there must never be more than one row for this primary key — a wrongful "
                        + "DELETE+INSERT re-use would either leave zero rows momentarily or, if "
                        + "buggy, could leave two")
                .isEqualTo(1);

        IdempotencyRecord afterReuse = readRecord(institutionId, key);
        assertThat(afterReuse.createdAt())
                .as("created_at must never change on reuse — that is exactly what distinguishes an "
                        + "UPDATE from a DELETE followed by an INSERT, which the requirement forbids")
                .isEqualTo(originalCreatedAt);
        assertThat(afterReuse.status()).isEqualTo("COMPLETED");
        assertThat(afterReuse.expiresAt()).isAfter(T1);
    }

    private IdempotencyRecord readRecord(InstitutionId institutionId, IdempotencyKey key) {
        return transactionRunner().execute(contextOf(institutionId),
                () -> store().lockExisting(institutionId, key).orElseThrow());
    }

    private long countRowsForPrimaryKey(InstitutionId institutionId, IdempotencyKey key) {
        return transactionRunner().execute(contextOf(institutionId), () -> dsl.fetchCount(dsl
                .selectFrom(SHARED_IDEMPOTENCY_KEY)
                .where(SHARED_IDEMPOTENCY_KEY.INSTITUTION_ID.eq(institutionId.value()))
                .and(SHARED_IDEMPOTENCY_KEY.ENDPOINT.eq(key.endpoint()))
                .and(SHARED_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY.eq(key.value()))));
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
