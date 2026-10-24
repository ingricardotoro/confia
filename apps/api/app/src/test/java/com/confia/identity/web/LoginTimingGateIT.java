package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.kernel.InstitutionId;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The timing gate of the login (specs/identity, scenario I159; specs/build-integrity, scenarios BI56
 * and BI57; ADR-0005, verification 7): 200 attempts with an existing account and a wrong password and
 * 200 with identifiers that are not accounts, against the real administrative process and a real
 * PostgreSQL, and the medians of the response times differ by 50 ms at most. The process runs the
 * production code with the Argon2id floor profile of the tests; two properties take the waiting out
 * of the measurement without touching the code under test: the cap of the required delay is one
 * millisecond, so that the growing delay of the backoff of the one existing account is not part of
 * what is compared, and the limits of the policy are raised, so that the limiter never answers {@code
 * 429} in the middle of the run. Every request comes from a client address of its own.
 *
 * <p>The gate compares medians and waits for nothing, so it is neither a sleep nor a race: the two
 * kinds of attempt alternate, and a slow machine slows both. Its negative control (BI56) is the
 * same gate with the unknown-account branch replaced by an immediate answer, which is what omitting
 * the decoy verification amounts to: it must fail, naming the difference it measured.
 */
class LoginTimingGateIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String EXISTING = "puerta.tiempo@colegio.edu.hn";
    private static final String WRONG_PASSWORD = "contrasena-erronea-1";
    private static final int ATTEMPTS = 200;
    private static final int WARM_UP = 10;
    private static final Duration LIMIT = Duration.ofMillis(50);

    private static LoginProcess process;

    @BeforeAll
    static void startTheProcess() {
        process = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of(
                "confia.web.delay.max-wait", "1ms",
                "confia.web.rate-limit.admin-login.request-limit", "10000",
                "confia.web.rate-limit.admin-login.failure-threshold", "10000"));
    }

    @AfterAll
    static void stopTheProcess() {
        process.close();
    }

    @Override
    InstitutionId institution() {
        return INSTITUTION;
    }

    @Test
    void theMediansOfAnExistingAccountAndOfAnUnknownIdentifierDifferByFiftyMillisecondsAtMost() {
        seedAccount(EXISTING, false, false);

        Gate gate = measure(this::existingAccount, this::unknownIdentifier);

        assertThat(gate.difference())
                .as("median of the existing account: %d ms, median of the unknown identifier: %d ms",
                        gate.first().toMillis(), gate.second().toMillis())
                .isLessThanOrEqualTo(LIMIT);
    }

    /** BI56: an unknown identifier answered at once, which is what omitting the decoy amounts to. */
    @Test
    void theSameGateFailsWhenTheUnknownIdentifierIsAnsweredWithoutTheDecoyVerification() {
        seedAccount(EXISTING, false, false);

        Gate gate = measure(this::existingAccount, this::answeredAtOnce);

        assertThat(gate.difference())
                .as("the difference the gate measured: %d ms (median of the existing account %d ms, "
                        + "median of the immediate answer %d ms)", gate.difference().toMillis(),
                        gate.first().toMillis(), gate.second().toMillis())
                .isGreaterThan(LIMIT);
    }

    // ---------------------------------------------------------------------------------------------

    private record Gate(Duration first, Duration second) {

        Duration difference() {
            return first.minus(second).abs();
        }
    }

    private long existingAccount() {
        return timed(() -> process.login(LoginProcess.freshAddress(), EXISTING, WRONG_PASSWORD), 401);
    }

    private long unknownIdentifier() {
        return timed(() -> process.login(LoginProcess.freshAddress(),
                "nadie." + UUID.randomUUID() + "@colegio.edu.hn", WRONG_PASSWORD), 401);
    }

    /** The unknown identifier without the verification: refused by the edge before the use case. */
    private long answeredAtOnce() {
        return timed(() -> process.post("{", "X-Forwarded-For", LoginProcess.freshAddress()), 400);
    }

    private static long timed(Supplier<HttpResponse<String>> request, int expectedStatus) {
        long start = System.nanoTime();
        HttpResponse<String> response = request.get();
        long elapsed = System.nanoTime() - start;
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        return elapsed;
    }

    /** Alternates the two kinds of attempt, after a warm-up of each, and compares their medians. */
    private static Gate measure(java.util.function.LongSupplier first,
            java.util.function.LongSupplier second) {
        for (int i = 0; i < WARM_UP; i++) {
            first.getAsLong();
            second.getAsLong();
        }
        List<Long> firstTimes = new ArrayList<>();
        List<Long> secondTimes = new ArrayList<>();
        for (int i = 0; i < ATTEMPTS; i++) {
            firstTimes.add(first.getAsLong());
            secondTimes.add(second.getAsLong());
        }
        return new Gate(median(firstTimes), median(secondTimes));
    }

    private static Duration median(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return Duration.ofNanos((sorted.get(middle - 1) + sorted.get(middle)) / 2);
    }
}
