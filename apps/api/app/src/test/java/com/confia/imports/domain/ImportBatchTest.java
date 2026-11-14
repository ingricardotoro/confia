package com.confia.imports.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The counts of a batch add up, a simulation creates nothing and an application created what it says. */
class ImportBatchTest {

    private static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");
    private static final String SHA = "a".repeat(64);
    private static final UUID ACCOUNT = UUID.randomUUID();

    @Test
    void aSimulationCreatesNothing() {
        ImportBatch batch = ImportBatch.simulated(UUID.randomUUID(), ImportKind.STUDENTS, SHA, 10, 6, 3, 1, ACCOUNT,
                NOW);

        assertThat(batch.status()).isEqualTo(ImportStatus.VALIDATED);
        assertThat(batch.createdRows()).isZero();
        assertThat(batch.appliedAt()).isNull();
        assertThat(batch.simulationId()).isNull();
    }

    @Test
    void anApplicationHasCreatedWhatWasValidAndPointsToItsSimulation() {
        UUID simulation = UUID.randomUUID();

        ImportBatch batch = ImportBatch.applied(UUID.randomUUID(), ImportKind.STUDENTS, SHA, 10, 6, 3, 1, simulation,
                ACCOUNT, NOW);

        assertThat(batch.validRows()).isEqualTo(6).isEqualTo(batch.createdRows());
        assertThat(batch.appliedAt()).isEqualTo(NOW);
        assertThat(batch.simulationId()).isEqualTo(simulation);
    }

    @Test
    void theCountsMustAddUpToTheTotal() {
        assertThatThrownBy(() -> ImportBatch.simulated(UUID.randomUUID(), ImportKind.STUDENTS, SHA, 10, 6, 3, 2,
                ACCOUNT, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportBatch.simulated(UUID.randomUUID(), ImportKind.STUDENTS, SHA, 10, 11, -1, 0,
                ACCOUNT, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyCountMustBeZeroOrMore() {
        assertThatThrownBy(() -> simulated(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> simulated(0, -1, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> simulated(0, 0, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> simulated(0, 0, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), ImportKind.STUDENTS, ImportStatus.APPLIED, SHA,
                0, 0, 0, -1, 0, UUID.randomUUID(), ACCOUNT, NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    private static ImportBatch simulated(int total, int valid, int rejected, int skipped) {
        return ImportBatch.simulated(UUID.randomUUID(), ImportKind.STUDENTS, SHA, total, valid, rejected, skipped,
                ACCOUNT, NOW);
    }

    @Test
    void aSimulationCannotPointToAnotherSimulation() {
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), ImportKind.STUDENTS, ImportStatus.VALIDATED, SHA,
                10, 6, 3, 0, 1, UUID.randomUUID(), ACCOUNT, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSimulationWithCreatedRowsOrAnApplicationTimeIsRefused() {
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), ImportKind.STUDENTS, ImportStatus.VALIDATED, SHA,
                10, 6, 3, 6, 1, null, ACCOUNT, NOW, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), ImportKind.STUDENTS, ImportStatus.VALIDATED, SHA,
                10, 6, 3, 0, 1, null, ACCOUNT, NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anApplicationThatDidNotCreateWhatWasValidIsRefused() {
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), ImportKind.STUDENTS, ImportStatus.APPLIED, SHA,
                10, 6, 3, 5, 1, UUID.randomUUID(), ACCOUNT, NOW, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ImportBatch(UUID.randomUUID(), ImportKind.STUDENTS, ImportStatus.APPLIED, SHA,
                10, 6, 3, 6, 1, UUID.randomUUID(), ACCOUNT, NOW, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyFileHasAZeroBatchAndAnAlreadyImportedRejectionIsRecognised() {
        assertThat(ImportBatch.simulated(UUID.randomUUID(), ImportKind.STUDENTS, SHA, 0, 0, 0, 0, ACCOUNT, NOW)
                .totalRows()).isZero();
        assertThat(new Rejection(2, "legacyId", "already-imported").isAlreadyImported()).isTrue();
        assertThat(new Rejection(2, "legacyId", "required").isAlreadyImported()).isFalse();
    }
}
