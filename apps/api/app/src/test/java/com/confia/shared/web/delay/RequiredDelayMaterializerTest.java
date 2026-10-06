package com.confia.shared.web.delay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.confia.shared.web.problem.CapacityExceededException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Specs/web-edge, requirements "El retardo requerido se materializa después de confirmar la
 * transacción", "El retardo se suma al tiempo de procesamiento", "Un semáforo acotado limita las
 * esperas y responde 503 uniforme antes de procesar" and "El cierre de la conexión del cliente
 * abandona la espera" (web-edge-foundations design.md, decision 18). Every call runs on a virtual
 * thread, which the materializer requires, and every wait is held or released by a latch of a
 * {@link GatedTimer}: no test waits for a clock.
 */
class RequiredDelayMaterializerTest {

    private static final Duration HALF_A_SECOND = Duration.ofMillis(500);
    private static final long SAFETY_SECONDS = 30;
    private static final String PROPERTY = "confia.web.delay.max-concurrent-waits";

    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stopTheThreads() {
        virtualThreads.shutdownNow();
    }

    // --- The permit comes before the use case ---

    @Test
    void thePermitIsTakenBeforeTheUseCaseRuns() throws Exception {
        RequiredDelayMaterializer materializer =
                new RequiredDelayMaterializer(2, GatedTimer.immediate());
        AtomicInteger permitsDuringTheUseCase = new AtomicInteger(-1);

        run(() -> materializer.execute(() -> {
            permitsDuringTheUseCase.set(materializer.availablePermits());
            return new Delayed<>("done", Duration.ZERO);
        }));

        assertThat(permitsDuringTheUseCase).as("one of the two permits was already held")
                .hasValue(1);
    }

    @Test
    void aRequestWithAllThePermitsInUseIsRefusedBeforeTheUseCaseRuns() throws Exception {
        GatedTimer timer = GatedTimer.gated(2);
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(2, timer);
        AtomicInteger waitingRuns = new AtomicInteger();
        Future<String> first = start(() -> waiting(materializer, waitingRuns));
        Future<String> second = start(() -> waiting(materializer, waitingRuns));
        timer.awaitEntered();
        AtomicInteger refusedRuns = new AtomicInteger();

        Future<String> refused = start(() -> materializer.execute(() -> {
            refusedRuns.incrementAndGet();
            return new Delayed<>("never", Duration.ZERO);
        }));

        assertThatThrownBy(() -> refused.get(SAFETY_SECONDS, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CapacityExceededException.class);
        assertThat(refusedRuns).as("the use case of the refused request never ran").hasValue(0);
        assertThat(waitingRuns).as("only the two admitted requests ran").hasValue(2);
        assertThat(materializer.availablePermits()).isZero();
        timer.open();
        assertThat(first.get(SAFETY_SECONDS, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(second.get(SAFETY_SECONDS, TimeUnit.SECONDS)).isEqualTo("done");
    }

    @Test
    void aPermitFreedByAFinishedWaitAdmitsTheNextRequest() throws Exception {
        GatedTimer timer = GatedTimer.gated(1);
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(1, timer);
        Future<String> first = start(() -> waiting(materializer, new AtomicInteger()));
        timer.awaitEntered();
        Future<String> refused = start(() -> waiting(materializer, new AtomicInteger()));
        assertThatThrownBy(() -> refused.get(SAFETY_SECONDS, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CapacityExceededException.class);

        timer.open();
        first.get(SAFETY_SECONDS, TimeUnit.SECONDS);

        assertThat(run(() -> waiting(materializer, new AtomicInteger()))).isEqualTo("done");
    }

    @Test
    void thePermitIsReleasedWhenTheUseCaseThrows() throws Exception {
        RequiredDelayMaterializer materializer =
                new RequiredDelayMaterializer(1, GatedTimer.immediate());

        assertThatThrownBy(() -> run(() -> materializer.execute(() -> {
            throw new IllegalArgumentException("the use case failed");
        }))).hasCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage("the use case failed");

        assertThat(materializer.availablePermits())
                .as("the only permit came back, so the failure left no wait behind").isEqualTo(1);
        assertThat(run(() -> materializer.execute(
                () -> new Delayed<>("next", Duration.ZERO)))).isEqualTo("next");
    }

    // --- Zero and negative delay ---

    @ParameterizedTest(name = "{0} ms")
    @ValueSource(longs = {0, -1, -500})
    void aDelayOfZeroOrLessAnswersWithoutWaitingAndKeepsEveryPermit(long milliseconds)
            throws Exception {
        GatedTimer timer = GatedTimer.immediate();
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(3, timer);

        String answer = run(() -> materializer.execute(
                () -> new Delayed<>("done", Duration.ofMillis(milliseconds))));

        assertThat(answer).isEqualTo("done");
        assertThat(timer.requested()).as("no wait was asked for").isEmpty();
        assertThat(materializer.availablePermits()).isEqualTo(3);
    }

    // --- The wait: after the use case, in full, on a virtual thread ---

    @Test
    void thePositiveDelayIsWaitedAfterTheUseCaseReturnsAndTheAnswerComesOnlyAfterIt()
            throws Exception {
        GatedTimer timer = GatedTimer.gated(1);
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(2, timer);
        List<String> events = new CopyOnWriteArrayList<>();

        Future<String> answer = start(() -> materializer.execute(() -> {
            events.add("use case returned");
            return new Delayed<>("done", HALF_A_SECOND);
        }));
        timer.awaitEntered();

        assertThat(events).containsExactly("use case returned");
        assertThat(timer.requested()).containsExactly(HALF_A_SECOND);
        assertThat(answer.isDone()).as("no answer while the wait is held").isFalse();
        assertThat(materializer.availablePermits()).as("the wait holds its permit").isEqualTo(1);
        timer.open();
        assertThat(answer.get(SAFETY_SECONDS, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(materializer.availablePermits()).isEqualTo(2);
    }

    @Test
    void theWaitRunsOnAVirtualThread() throws Exception {
        GatedTimer timer = GatedTimer.immediate();
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(1, timer);

        run(() -> materializer.execute(() -> new Delayed<>("done", HALF_A_SECOND)));

        assertThat(timer.virtualThreads()).containsExactly(true);
    }

    @Test
    void aSlowUseCaseDoesNotShortenTheWait() throws Exception {
        AtomicLong clockMillis = new AtomicLong();
        List<Duration> asked = new CopyOnWriteArrayList<>();
        AtomicLong clockWhenTheWaitBegan = new AtomicLong(-1);
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(1, delay -> {
            asked.add(delay);
            clockWhenTheWaitBegan.set(clockMillis.get());
        });

        run(() -> materializer.execute(() -> {
            clockMillis.addAndGet(300);
            return new Delayed<>("done", Duration.ofSeconds(1));
        }));

        assertThat(asked).as("the whole second, not the 700 ms that remain of it")
                .containsExactly(Duration.ofSeconds(1));
        assertThat(clockWhenTheWaitBegan).as("the wait begins when the processing ends")
                .hasValue(300);
    }

    @Test
    void theProductionTimerWaitsAtLeastTheRequestedTime() throws Exception {
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(1);
        Duration delay = Duration.ofMillis(25);

        long elapsed = run(() -> {
            long start = System.nanoTime();
            materializer.execute(() -> new Delayed<>("done", delay));
            return System.nanoTime() - start;
        });

        assertThat(Duration.ofNanos(elapsed)).isGreaterThanOrEqualTo(delay);
    }

    @Test
    void anInterruptedWaitReleasesThePermitAndKeepsTheInterruption() throws Exception {
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(1, delay -> {
            throw new InterruptedException("the server is shutting down");
        });

        boolean interruptedAfterwards = run(() -> {
            try {
                materializer.execute(() -> new Delayed<>("done", HALF_A_SECOND));
                return false;
            } catch (IllegalStateException interrupted) {
                return Thread.currentThread().isInterrupted();
            }
        });

        assertThat(interruptedAfterwards).as("the interruption is not swallowed").isTrue();
        assertThat(materializer.availablePermits()).isEqualTo(1);
    }

    // --- What the materializer refuses to do ---

    @Test
    void aPlatformThreadIsRefusedBeforeTheUseCaseRuns() {
        RequiredDelayMaterializer materializer =
                new RequiredDelayMaterializer(1, GatedTimer.immediate());
        AtomicInteger runs = new AtomicInteger();

        assertThat(Thread.currentThread().isVirtual()).as("this test runs on a platform thread")
                .isFalse();
        assertThatThrownBy(() -> materializer.execute(() -> {
            runs.incrementAndGet();
            return new Delayed<>("done", HALF_A_SECOND);
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("virtual thread");

        assertThat(runs).as("nothing was processed on a thread the wait would hold").hasValue(0);
        assertThat(materializer.availablePermits()).isEqualTo(1);
    }

    @Test
    void anActiveTransactionIsRefusedBeforeTheUseCaseRuns() throws Exception {
        RequiredDelayMaterializer materializer =
                new RequiredDelayMaterializer(1, GatedTimer.immediate());
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> run(() -> insideAnActiveTransaction(() -> materializer
                .execute(() -> {
                    runs.incrementAndGet();
                    return new Delayed<>("done", HALF_A_SECOND);
                })))).hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("the delay must be materialized outside any transaction");

        assertThat(runs).hasValue(0);
        assertThat(materializer.availablePermits()).isEqualTo(1);
    }

    @Test
    void aTransactionLeftOpenByTheUseCaseIsRefusedBeforeTheWait() throws Exception {
        GatedTimer timer = GatedTimer.immediate();
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(1, timer);

        assertThatThrownBy(() -> run(() -> {
            try {
                return materializer.execute(() -> {
                    TransactionSynchronizationManager.setActualTransactionActive(true);
                    return new Delayed<>("done", HALF_A_SECOND);
                });
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        })).hasCauseInstanceOf(IllegalStateException.class);

        assertThat(timer.requested()).as("the wait was never asked for").isEmpty();
        assertThat(materializer.availablePermits()).isEqualTo(1);
    }

    @Test
    void aUseCaseThatAnswersNothingReleasesThePermit() throws Exception {
        RequiredDelayMaterializer materializer =
                new RequiredDelayMaterializer(1, GatedTimer.immediate());

        assertThatThrownBy(() -> run(() -> materializer.execute(() -> null)))
                .hasCauseInstanceOf(NullPointerException.class);

        assertThat(materializer.availablePermits()).isEqualTo(1);
    }

    @Test
    void aDelayWithoutADurationIsRefused() {
        assertThatThrownBy(() -> new Delayed<>("value", null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest(name = "{0} permits")
    @ValueSource(ints = {0, -1})
    void aNumberOfPermitsThatIsNotPositiveIsRefusedNamingTheProperty(int permits) {
        assertThatThrownBy(() -> new RequiredDelayMaterializer(permits, GatedTimer.immediate()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(PROPERTY);
    }

    // --- The client that leaves ---

    @Test
    void aClientThatLeftChangesNeitherTheWaitNorTheReleaseNorTheLog() throws Exception {
        GatedTimer timer = GatedTimer.gated(1);
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(1, timer);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logged.start();
        root.addAppender(logged);
        try {
            // The request is in its wait and the client closes its connection: nothing tells the
            // server, so the permit stays taken until the timer releases the wait.
            Future<String> answer = start(() -> {
                materializer.execute(() -> new Delayed<>("done", HALF_A_SECOND));
                // The write of the answer fails because the client is gone.
                throw new UncheckedIOException(new IOException("Broken pipe"));
            });
            timer.awaitEntered();

            assertThat(materializer.availablePermits())
                    .as("the permit is not released before the delay expires").isZero();
            timer.open();
            assertThatThrownBy(() -> answer.get(SAFETY_SECONDS, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(UncheckedIOException.class);

            assertThat(materializer.availablePermits())
                    .as("the permit came back although the write failed").isEqualTo(1);
            assertThat(logged.list).as("the materializer logged nothing at ERROR")
                    .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.ERROR));
        } finally {
            root.detachAppender(logged);
        }
    }

    // --- Helpers ---

    private static String waiting(RequiredDelayMaterializer materializer, AtomicInteger runs) {
        return materializer.execute(() -> {
            runs.incrementAndGet();
            return new Delayed<>("done", HALF_A_SECOND);
        });
    }

    /** Marks a transaction active on this thread for the call, and always clears the mark. */
    private static <T> T insideAnActiveTransaction(Callable<T> call) throws Exception {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            return call.call();
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    private <T> Future<T> start(Callable<T> call) {
        return virtualThreads.submit(call);
    }

    /** Runs {@code call} on a virtual thread; a failure arrives as the cause of the exception. */
    private <T> T run(Callable<T> call) throws Exception {
        return start(call).get(SAFETY_SECONDS, TimeUnit.SECONDS);
    }
}
