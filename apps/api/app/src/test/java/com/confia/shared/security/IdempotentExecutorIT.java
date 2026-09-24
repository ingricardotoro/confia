package com.confia.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.confia.kernel.InstitutionId;
import com.confia.shared.infrastructure.JooqIdempotencyRecordStore;
import com.confia.support.CommittingPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link IdempotentExecutor} without real concurrency (design.md, decisions 1, 6 and 7): the four
 * sequential paths a single caller can exercise inside one transaction — a new key, the atomicity of
 * the marker and its business effect, the replay of an already-completed key, and the rejection of a
 * different payload under the same key. The bounded {@code lock_timeout} and its three {@code
 * SQLState} outcomes under real concurrency, plus the non-retry proof, live in {@link
 * IdempotentExecutorConcurrencyIT} (design.md, decision 4; tasks.md, tasks 5.2-5.4).
 */
class IdempotentExecutorIT extends CommittingPostgresIntegrationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final String ENDPOINT = "/api/v1/payments";
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private IdempotencyRecordStore store() {
        return new JooqIdempotencyRecordStore(dsl);
    }

    private IdempotentExecutor executor() {
        return new IdempotentExecutor(transactionRunner(), store(), new RequestPayloadHasher(),
                FIXED_CLOCK, dataSource());
    }

    @Test
    void newKeyExecutesTheUseCaseAndCompletesTheMarkerInTheSameTransaction() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"100.0000\"}");
        int[] invocations = new int[1];
        IdempotentResponse expected =
                new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"a\"}"));

        IdempotentOutcome outcome =
                executor().execute(contextOf(institutionId), key, payload, () -> {
                    invocations[0]++;
                    return expected;
                });

        assertThat(outcome).isInstanceOf(IdempotentOutcome.Executed.class);
        assertThat(outcome.response()).isEqualTo(expected);
        assertThat(invocations[0]).isEqualTo(1);

        IdempotencyRecord stored = transactionRunner().execute(contextOf(institutionId),
                () -> store().lockExisting(institutionId, key).orElseThrow());
        assertThat(stored.status()).isEqualTo("COMPLETED");
        assertThat(stored.responseStatus()).isEqualTo(201);
        assertThat(stored.completedAt()).isNotNull();
    }

    @Test
    void aUseCaseThatFailsAfterTheMarkerRevertsBothAndALaterRequestIsTreatedAsNew() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"50.0000\"}");

        assertThatThrownBy(() -> executor().execute(contextOf(institutionId), key, payload, () -> {
            throw new IllegalStateException("deterministic business failure after the marker write");
        })).isInstanceOf(IllegalStateException.class);

        Optional<IdempotencyRecord> afterFailure = transactionRunner()
                .execute(contextOf(institutionId), () -> store().lockExisting(institutionId, key));
        assertThat(afterFailure)
                .as("neither the marker nor the business effect must survive a failed use case: "
                        + "both writes share one transaction")
                .isEmpty();

        int[] invocations = new int[1];
        IdempotentResponse response = new IdempotentResponse(200, JSON_MAPPER.readTree("{}"));

        IdempotentOutcome outcome =
                executor().execute(contextOf(institutionId), key, payload, () -> {
                    invocations[0]++;
                    return response;
                });

        assertThat(outcome)
                .as("a later request with the same key, after the failed transaction rolled back, "
                        + "must be treated as a brand new key")
                .isInstanceOf(IdempotentOutcome.Executed.class);
        assertThat(invocations[0]).isEqualTo(1);
    }

    @Test
    void sameKeyAndSameHashOnACompletedKeyReplaysWithoutReexecutingTheUseCase() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode payload = JSON_MAPPER.readTree("{\"amount\":\"75.0000\"}");
        int[] invocations = new int[1];
        IdempotentResponse storedResponse =
                new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"b\"}"));

        IdempotentOutcome first =
                executor().execute(contextOf(institutionId), key, payload, () -> {
                    invocations[0]++;
                    return storedResponse;
                });
        assertThat(first).isInstanceOf(IdempotentOutcome.Executed.class);

        IdempotentOutcome replay =
                executor().execute(contextOf(institutionId), key, payload, () -> {
                    invocations[0]++;
                    return new IdempotentResponse(500, JSON_MAPPER.readTree("{}"));
                });

        assertThat(replay).isInstanceOf(IdempotentOutcome.Replayed.class);
        assertThat(replay.response()).isEqualTo(storedResponse);
        assertThat(invocations[0])
                .as("the use case must record zero new invocations on replay")
                .isEqualTo(1);
    }

    @Test
    void sameKeyDifferentHashIsRejectedWithoutInvokingTheUseCaseAtAll() {
        InstitutionId institutionId = new InstitutionId(UUID.randomUUID());
        IdempotencyKey key = new IdempotencyKey(ENDPOINT, UUID.randomUUID().toString());
        JsonNode firstPayload = JSON_MAPPER.readTree("{\"amount\":\"10.0000\"}");
        JsonNode differentPayload = JSON_MAPPER.readTree("{\"amount\":\"20.0000\"}");
        IdempotentResponse storedResponse =
                new IdempotentResponse(201, JSON_MAPPER.readTree("{\"id\":\"c\"}"));

        executor().execute(contextOf(institutionId), key, firstPayload, () -> storedResponse);

        int[] invocations = new int[1];
        assertThatThrownBy(() -> executor().execute(contextOf(institutionId), key,
                differentPayload, () -> {
                    invocations[0]++;
                    return storedResponse;
                })).isInstanceOf(IdempotencyPayloadMismatchException.class);

        assertThat(invocations[0])
                .as("a payload-hash mismatch must reject before the use case ever runs, verifiable "
                        + "by an invocation counter and not only by the exception type")
                .isEqualTo(0);
    }

    private static SecurityContext contextOf(InstitutionId institutionId) {
        return new SecurityContext("", "system", institutionId.value().toString(),
                UUID.randomUUID().toString());
    }
}
