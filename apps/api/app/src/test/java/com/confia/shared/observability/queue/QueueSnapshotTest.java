package com.confia.shared.observability.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The value the queue reader hands to the health rule and to the log adapter. */
class QueueSnapshotTest {

    @Test
    void theEmptySnapshotHasNothingInAnyState() {
        QueueSnapshot empty = QueueSnapshot.empty();

        assertThat(empty.scheduled()).isZero();
        assertThat(empty.due()).isZero();
        assertThat(empty.running()).isZero();
        assertThat(empty.failed()).isZero();
        assertThat(empty.failedByTask()).isEmpty();
        assertThat(empty.oldestDueAge()).isEqualTo(Duration.ZERO);
        assertThat(empty.oldestDueAgeSeconds()).isZero();
    }

    @Test
    void theFailedCountIsTheSumOfTheFailuresOfEveryTask() {
        QueueSnapshot snapshot = new QueueSnapshot(1, 2, 3, Map.of("a.one", 2L, "b.two", 5L), Duration.ZERO);

        assertThat(snapshot.failed()).isEqualTo(7);
    }

    @Test
    void theAgeInSecondsDropsTheFraction() {
        QueueSnapshot snapshot = new QueueSnapshot(0, 1, 0, Map.of(), Duration.ofMillis(120_999));

        assertThat(snapshot.oldestDueAgeSeconds()).isEqualTo(120);
    }

    @Test
    void theFailuresAreCopiedAndSortedByTaskName() {
        Map<String, Long> source = new HashMap<>();
        source.put("b.two", 1L);
        source.put("a.one", 1L);
        QueueSnapshot snapshot = new QueueSnapshot(0, 0, 0, source, Duration.ZERO);
        source.put("c.three", 9L);

        assertThat(snapshot.failedByTask()).containsExactly(Map.entry("a.one", 1L), Map.entry("b.two", 1L));
        assertThatThrownBy(() -> snapshot.failedByTask().put("d.four", 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNegativeCountOrAgeIsRefused() {
        assertThatThrownBy(() -> new QueueSnapshot(-1, 0, 0, Map.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueSnapshot(0, -1, 0, Map.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueSnapshot(0, 0, -1, Map.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueSnapshot(0, 0, 0, Map.of("a.one", -1L), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueSnapshot(0, 0, 0, Map.of(), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingPartIsRefused() {
        assertThatThrownBy(() -> new QueueSnapshot(0, 0, 0, null, Duration.ZERO))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new QueueSnapshot(0, 0, 0, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }
}
