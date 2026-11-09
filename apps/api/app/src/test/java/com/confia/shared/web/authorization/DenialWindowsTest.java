package com.confia.shared.web.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.Permission;
import com.confia.shared.web.authorization.DenialWindows.Decision;
import com.confia.shared.web.authorization.DenialWindows.Key;
import com.confia.shared.web.authorization.DenialWindows.PendingSummary;
import com.confia.shared.web.harness.MutableClock;
import com.confia.shared.web.harness.RecordingAuthorizationMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The cap, the window and the summary of the permission denials in memory
 * (rbac-permission-matrix-and-audit-integration design.md D10; scenarios AZ52, AZ54 to AZ56, AZ79,
 * AZ80, and the checks DC-1 and DC-2). Every test has its own instance and its own manual clock, uses
 * no real time and no wait: the concurrency is a latch that releases every thread at once.
 */
class DenialWindowsTest {

    private static final Instant START = Instant.parse("2026-11-09T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int CAP = 3;

    private final MutableClock clock = new MutableClock(START);
    private final RecordingAuthorizationMetrics metrics = new RecordingAuthorizationMetrics();

    private static Key key(UUID institution, UUID account, Permission permission) {
        return new Key(institution, account, permission);
    }

    private static Key anyKey() {
        return key(UUID.randomUUID(), UUID.randomUUID(), Permission.IDENTITY_READ);
    }

    private DenialWindows windows() {
        return new DenialWindows(CAP, WINDOW, 10_000, 10_000, 500, metrics);
    }

    private static int total(List<PendingSummary> summaries) {
        return summaries.stream().mapToInt(PendingSummary::omittedCount).sum();
    }

    // --- AZ52: the cap of one key ---

    @Test
    void upToTheCapEveryDenialIsAFullRowAndTheRestAreOmitted() {
        DenialWindows windows = windows();
        Key key = anyKey();

        List<Decision.Kind> kinds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            kinds.add(windows.record(key, clock.instant()).kind());
        }

        assertThat(kinds).containsExactly(Decision.Kind.FULL_ROW, Decision.Kind.FULL_ROW,
                Decision.Kind.FULL_ROW, Decision.Kind.OMITTED, Decision.Kind.OMITTED);
    }

    @Test
    void aFullRowCarriesTheStartOfItsWindow() {
        DenialWindows windows = windows();
        Key key = anyKey();

        Decision first = windows.record(key, clock.instant());
        clock.advance(Duration.ofSeconds(30));
        Decision second = windows.record(key, clock.instant());

        assertThat(first.windowStart()).isEqualTo(START);
        assertThat(second.windowStart()).isEqualTo(START);
    }

    // --- AZ54: independent counters ---

    @Test
    void countersAreIndependentPerAccountPerPermissionAndPerInstitution() {
        DenialWindows windows = windows();
        UUID institution = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        Key base = key(institution, account, Permission.IDENTITY_READ);
        for (int i = 0; i < CAP; i++) {
            windows.record(base, clock.instant());
        }
        assertThat(windows.record(base, clock.instant()).kind()).isEqualTo(Decision.Kind.OMITTED);

        assertThat(windows.record(key(institution, UUID.randomUUID(), Permission.IDENTITY_READ),
                clock.instant()).kind()).as("other account").isEqualTo(Decision.Kind.FULL_ROW);
        assertThat(windows.record(key(institution, account, Permission.PAYMENTS_CREATE),
                clock.instant()).kind()).as("other permission").isEqualTo(Decision.Kind.FULL_ROW);
        assertThat(windows.record(key(UUID.randomUUID(), account, Permission.IDENTITY_READ),
                clock.instant()).kind()).as("same account id in another institution")
                .isEqualTo(Decision.Kind.FULL_ROW);
    }

    // --- AZ55: the window closes ---

    @Test
    void whenTheWindowIsOverTheCountStartsAgainAndItsSummaryIsQueued() {
        DenialWindows windows = windows();
        Key key = anyKey();
        for (int i = 0; i < 5; i++) {
            windows.record(key, clock.instant());
        }

        clock.set(START.plus(WINDOW).minusMillis(1));
        assertThat(windows.record(key, clock.instant()).kind()).as("one millisecond before")
                .isEqualTo(Decision.Kind.OMITTED);
        clock.set(START.plus(WINDOW));
        Decision reopened = windows.record(key, clock.instant());

        assertThat(reopened.kind()).as("at the exact end").isEqualTo(Decision.Kind.FULL_ROW);
        assertThat(reopened.windowStart()).isEqualTo(clock.instant());
        assertThat(windows.pollPending()).hasValueSatisfying(summary -> {
            assertThat(summary.key()).isEqualTo(key);
            assertThat(summary.windowStart()).isEqualTo(START);
            assertThat(summary.omittedCount()).isEqualTo(3);
        });
        assertThat(windows.pollPending()).isEmpty();
    }

    @Test
    void aWindowWithoutOmissionsQueuesNothingWhenItCloses() {
        DenialWindows windows = windows();
        Key key = anyKey();
        windows.record(key, clock.instant());

        clock.advance(WINDOW);
        windows.record(key, clock.instant());

        assertThat(windows.pendingSize()).isZero();
    }

    // --- AZ56: the bound of tracked keys ---

    @Test
    void theBoundOfKeysIsNeverPassedAndANewKeyWithAFullStateIsUntrackedAndEvictsNone() {
        DenialWindows windows = new DenialWindows(CAP, WINDOW, 10, 10_000, 500, metrics);
        List<Key> tracked = new ArrayList<>();
        List<Decision.Kind> kinds = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            Key key = anyKey();
            Decision decision = windows.record(key, clock.instant());
            kinds.add(decision.kind());
            if (decision.kind() == Decision.Kind.FULL_ROW) {
                tracked.add(key);
            }
            assertThat(windows.size()).isLessThanOrEqualTo(10);
        }

        assertThat(kinds.subList(0, 10)).containsOnly(Decision.Kind.FULL_ROW);
        assertThat(kinds.subList(10, 25)).containsOnly(Decision.Kind.UNTRACKED);
        assertThat(windows.size()).isEqualTo(10);
        for (int i = 0; i < CAP; i++) {
            assertThat(windows.record(tracked.get(0), clock.instant()).kind())
                    .as("a tracked key was not evicted: it counts on").isNotEqualTo(
                            Decision.Kind.UNTRACKED);
        }
    }

    @Test
    void aNewKeyAtTheBoundSweepsTheClosedWindowsOnceAndRetriesOnce() {
        DenialWindows windows = new DenialWindows(CAP, WINDOW, 10, 10_000, 500, metrics);
        for (int i = 0; i < 10; i++) {
            windows.record(anyKey(), clock.instant());
        }
        clock.advance(WINDOW.plusSeconds(1));

        Decision decision = windows.record(anyKey(), clock.instant());

        assertThat(decision.kind()).isEqualTo(Decision.Kind.FULL_ROW);
        assertThat(windows.size()).isEqualTo(1);
    }

    // --- AZ79: the cap under concurrency ---

    @Test
    void concurrentDenialsOfOneKeyNeverExceedTheCap() throws Exception {
        DenialWindows windows = windows();
        Key key = anyKey();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Decision.Kind>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return windows.record(key, START).kind();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Decision.Kind> kinds = new ArrayList<>();
            for (Future<Decision.Kind> result : results) {
                kinds.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(kinds.stream().filter(kind -> kind == Decision.Kind.FULL_ROW).count())
                    .isEqualTo(CAP);
            assertThat(kinds.stream().filter(kind -> kind == Decision.Kind.OMITTED).count())
                    .isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sweepRacingARecordOfTheSameKeyLosesNoDenial() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 300; round++) {
                DenialWindows windows = windows();
                Key key = anyKey();
                for (int i = 0; i < 5; i++) {
                    windows.record(key, START);
                }
                Instant later = START.plus(WINDOW).plusSeconds(1);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<?> sweep = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    windows.sweepNow(later);
                    return null;
                });
                Future<Decision.Kind> record = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return windows.record(key, later).kind();
                });
                assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                sweep.get(30, TimeUnit.SECONDS);
                Decision.Kind raced = record.get(30, TimeUnit.SECONDS);

                int fullRows = CAP + (raced == Decision.Kind.FULL_ROW ? 1 : 0);
                int counted = total(windows.closeAllForShutdown(later));

                assertThat(fullRows + counted).as("round %d: six denials in all", round)
                        .isEqualTo(6);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // --- AZ80: a full row that fails is not lost ---

    @Test
    void failedFullRowReturnsTheSlotAndCountsAsOmitted() {
        DenialWindows windows = windows();
        Key key = anyKey();
        Decision first = windows.record(key, clock.instant());
        windows.fullRowFailed(key, first.windowStart());

        List<Decision.Kind> next = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            next.add(windows.record(key, clock.instant()).kind());
        }

        assertThat(next).as("the slot of the failed row was returned").containsExactly(
                Decision.Kind.FULL_ROW, Decision.Kind.FULL_ROW, Decision.Kind.FULL_ROW,
                Decision.Kind.OMITTED);
        assertThat(total(windows.closeAllForShutdown(clock.instant()))).as("the failed row and "
                + "the omitted one, so that 3 rows written plus the count equal 5 denials")
                .isEqualTo(2);
    }

    @Test
    void failedFullRowOfAClosedWindowQueuesASummaryOfOne() {
        DenialWindows windows = windows();
        Key key = anyKey();
        Decision first = windows.record(key, clock.instant());
        clock.advance(WINDOW);
        windows.record(key, clock.instant());

        windows.fullRowFailed(key, first.windowStart());

        assertThat(windows.pollPending()).hasValueSatisfying(summary -> {
            assertThat(summary.key()).isEqualTo(key);
            assertThat(summary.windowStart()).isEqualTo(START);
            assertThat(summary.omittedCount()).isEqualTo(1);
        });
    }

    @Test
    void failedUntrackedRowQueuesASummaryOfOneAtTheInstantOfTheDenial() {
        DenialWindows windows = windows();
        Key key = anyKey();

        windows.untrackedRowFailed(key, clock.instant());

        assertThat(windows.pollPending()).hasValueSatisfying(summary -> {
            assertThat(summary.windowStart()).isEqualTo(START);
            assertThat(summary.omittedCount()).isEqualTo(1);
        });
    }

    // --- The pending queue ---

    @Test
    void pendingQueueDiscardsBeyondItsBound() {
        DenialWindows windows = new DenialWindows(CAP, WINDOW, 10_000, 2, 500, metrics);

        windows.untrackedRowFailed(anyKey(), clock.instant());
        windows.untrackedRowFailed(anyKey(), clock.instant());
        windows.untrackedRowFailed(anyKey(), clock.instant());

        assertThat(windows.pendingSize()).isEqualTo(2);
        assertThat(metrics.denialAuditFailedCount()).as("the discard is counted once").isEqualTo(1);
    }

    @Test
    void requeueGoesToTheHeadAndAFullQueueRefusesIt() {
        DenialWindows windows = new DenialWindows(CAP, WINDOW, 10_000, 2, 500, metrics);
        Key first = anyKey();
        Key second = anyKey();
        windows.untrackedRowFailed(first, clock.instant());
        windows.untrackedRowFailed(second, clock.instant());
        PendingSummary head = windows.pollPending().orElseThrow();
        assertThat(head.key()).isEqualTo(first);

        assertThat(windows.requeue(head)).isTrue();
        assertThat(windows.pollPending().orElseThrow().key()).as("back at the head").isEqualTo(first);
        windows.untrackedRowFailed(anyKey(), clock.instant());
        assertThat(windows.requeue(head)).as("the queue is full again").isFalse();
    }

    // --- The sweep ---

    @Test
    void sweepMovesAtMostItsLimitAndLeavesTheRest() {
        DenialWindows windows = new DenialWindows(CAP, WINDOW, 10_000, 10_000, 2, metrics);
        for (int i = 0; i < 5; i++) {
            windows.record(anyKey(), clock.instant());
        }
        clock.advance(WINDOW.plusSeconds(1));

        List<Integer> sizes = new ArrayList<>();
        for (int pass = 0; pass < 3; pass++) {
            windows.sweepIfDue(clock.instant());
            sizes.add(windows.size());
            clock.advance(Duration.ofSeconds(1));
        }

        assertThat(sizes).containsExactly(3, 1, 0);
    }

    @Test
    void aSweepQueuesTheSummaryOfAClosedWindowWithOmissionsAndOnlyThat() {
        DenialWindows windows = windows();
        Key noisy = anyKey();
        for (int i = 0; i < 5; i++) {
            windows.record(noisy, clock.instant());
        }
        windows.record(anyKey(), clock.instant());
        clock.advance(WINDOW.plusSeconds(1));

        windows.sweepIfDue(clock.instant());

        assertThat(windows.size()).isZero();
        assertThat(windows.pendingSize()).isEqualTo(1);
        assertThat(windows.pollPending().orElseThrow().omittedCount()).isEqualTo(2);
    }

    @Test
    void sweepRunsAtMostOncePerSecond() {
        DenialWindows windows = new DenialWindows(CAP, WINDOW, 10_000, 10_000, 1, metrics);
        for (int i = 0; i < 3; i++) {
            windows.record(anyKey(), clock.instant());
        }
        clock.advance(WINDOW.plusSeconds(1));

        windows.sweepIfDue(clock.instant());
        assertThat(windows.size()).as("the first sweep moves its one window").isEqualTo(2);
        clock.advance(Duration.ofMillis(999));
        windows.sweepIfDue(clock.instant());
        assertThat(windows.size()).as("the gate is shut for a second after a sweep").isEqualTo(2);

        clock.advance(Duration.ofMillis(1));
        windows.sweepIfDue(clock.instant());
        assertThat(windows.size()).as("one second later it opens").isEqualTo(1);
    }

    @Test
    void farFutureNextSweepInstantIsTreatedAsDue() {
        DenialWindows windows = windows();
        Instant hourBefore = START.minus(Duration.ofHours(1));
        windows.record(anyKey(), hourBefore.minus(WINDOW).minusSeconds(1));
        windows.sweepIfDue(START);
        windows.record(anyKey(), hourBefore.minus(WINDOW).minusSeconds(1));
        assertThat(windows.size()).isEqualTo(1);

        windows.sweepIfDue(hourBefore);

        assertThat(windows.size()).as("a step back of an hour does not stop the sweep for an hour")
                .isZero();
    }

    // --- DC-1: a clock that goes back ---

    @Test
    void backwardClockClosesTheWindowAndKeepsTheSum() {
        DenialWindows windows = windows();
        Key key = anyKey();
        for (int i = 0; i < 5; i++) {
            windows.record(key, START);
        }

        Instant before = START.minus(Duration.ofHours(1));
        Decision afterTheStep = windows.record(key, before);

        assertThat(afterTheStep.kind()).isEqualTo(Decision.Kind.FULL_ROW);
        assertThat(afterTheStep.windowStart()).isEqualTo(before);
        int fullRows = CAP + 1;
        int counted = total(windows.closeAllForShutdown(before));
        assertThat(fullRows + counted).as("never fewer rows than denials").isEqualTo(6);
    }

    // --- DC-2: the shutdown ---

    @Test
    void shutdownReturnsTheOpenWindowsWithOmissionsAndTheQueue() {
        DenialWindows windows = windows();
        Key open = anyKey();
        for (int i = 0; i < 5; i++) {
            windows.record(open, clock.instant());
        }
        windows.untrackedRowFailed(anyKey(), clock.instant());

        List<PendingSummary> closed = windows.closeAllForShutdown(clock.instant());

        assertThat(closed).hasSize(2);
        assertThat(total(closed)).isEqualTo(3);
        assertThat(windows.pendingSize()).isZero();
    }

    @Test
    void shutdownWithoutOmissionsReturnsNothing() {
        DenialWindows windows = windows();
        windows.record(anyKey(), clock.instant());

        assertThat(windows.closeAllForShutdown(clock.instant())).isEmpty();
    }

    // --- The constructor refuses a bad bound ---

    @Test
    void aNonPositiveBoundIsRefused() {
        for (int bad : new int[] {0, -1}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new DenialWindows(bad, WINDOW, 10, 10, 10, metrics))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new DenialWindows(CAP, WINDOW, bad, 10, 10, metrics))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new DenialWindows(CAP, WINDOW, 10, bad, 10, metrics))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new DenialWindows(CAP, WINDOW, 10, 10, bad, metrics))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new DenialWindows(CAP, Duration.ZERO, 10, 10, 10, metrics))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
