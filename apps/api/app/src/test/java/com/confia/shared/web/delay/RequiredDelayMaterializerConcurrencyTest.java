package com.confia.shared.web.delay;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.shared.web.problem.CapacityExceededException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Specs/web-edge, scenario "Concurrencia exacta del semáforo": {@code 2P} requests released
 * together by a {@link CyclicBarrier}, each with its wait held by a latch, and exactly {@code P} are
 * admitted. No sleep decides anything: the barrier makes the requests simultaneous, the held waits
 * keep the permits taken, and the test reads the outcome only after every refused request answered
 * and every admitted one is inside its wait.
 */
class RequiredDelayMaterializerConcurrencyTest {

    private static final long SAFETY_SECONDS = 30;
    private static final int PERMITS = 8;

    @Test
    void exactlyTheConfiguredNumberOfRequestsIsAdmittedWhenTwiceAsManyArriveTogether()
            throws Exception {
        GatedTimer timer = GatedTimer.gated(PERMITS);
        RequiredDelayMaterializer materializer = new RequiredDelayMaterializer(PERMITS, timer);
        int requests = 2 * PERMITS;
        CyclicBarrier together = new CyclicBarrier(requests);
        CountDownLatch refusals = new CountDownLatch(PERMITS);
        AtomicInteger useCaseRuns = new AtomicInteger();
        List<Future<String>> outcomes = new ArrayList<>();

        try (ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                outcomes.add(virtualThreads.submit(() -> {
                    together.await(SAFETY_SECONDS, TimeUnit.SECONDS);
                    try {
                        return materializer.execute(() -> {
                            useCaseRuns.incrementAndGet();
                            return new Delayed<>("admitted", Duration.ofSeconds(1));
                        });
                    } catch (CapacityExceededException refused) {
                        refusals.countDown();
                        return "refused";
                    }
                }));
            }

            // The P admitted requests are inside their wait and the P refused ones have answered.
            timer.awaitEntered();
            assertThat(refusals.await(SAFETY_SECONDS, TimeUnit.SECONDS)).isTrue();
            assertThat(useCaseRuns).as("only the admitted requests ran their use case")
                    .hasValue(PERMITS);
            assertThat(materializer.availablePermits()).isZero();
            timer.open();

            List<String> answers = new ArrayList<>();
            for (Future<String> outcome : outcomes) {
                answers.add(outcome.get(SAFETY_SECONDS, TimeUnit.SECONDS));
            }
            assertThat(answers.stream().filter("admitted"::equals)).hasSize(PERMITS);
            assertThat(answers.stream().filter("refused"::equals)).hasSize(PERMITS);
        }
        assertThat(materializer.availablePermits()).as("every permit came back").isEqualTo(PERMITS);
    }
}
