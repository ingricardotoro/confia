package com.confia.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.confia.identity.application.PasswordHasher;
import com.confia.identity.domain.PlainPassword;
import com.confia.identity.domain.StoredPasswordHash;
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
 *
 * <p>The limit the gate applies is the 50 ms of the specification or half of what one Argon2id
 * verification costs on the machine that runs it, whichever is smaller. A fixed 50 ms alone can only
 * see an omitted decoy that costs more than 50 ms: on a runner where the floor profile verifies in
 * 43 ms, the negative control measured 43 ms and the gate would have let the omission through. The
 * cost is measured on its own with the process's own {@code PasswordHasher} bean against its own
 * decoy hash, which is the very call the unknown-account branch makes, so the calibration cannot drift
 * from the verification it stands for; it is never taken from the two series the gate compares, so the
 * negative control is not true by construction. A verification cheaper than {@code MINIMUM_COST}
 * fails the calibration instead of shrinking the limit towards zero, where the gate on the real code
 * would turn into a coin toss.
 * The limit is never wider than 50 ms, so every run that passes also meets I159 as written, and half
 * the cost leaves the same margin to the equal answer of the real code and to the immediate answer
 * of the variant.
 */
class LoginTimingGateIT extends LoginEndpointSupport {

    private static final InstitutionId INSTITUTION = new InstitutionId(UUID.randomUUID());
    private static final String EXISTING = "puerta.tiempo@colegio.edu.hn";
    private static final String WRONG_PASSWORD = "contrasena-erronea-1";
    private static final int ATTEMPTS = 200;
    private static final int WARM_UP = 10;
    private static final Duration LIMIT = Duration.ofMillis(50);
    private static final int VERIFICATIONS = 30;
    private static final Duration MINIMUM_COST = Duration.ofMillis(10);

    private static LoginProcess process;
    private static Duration limit;

    @BeforeAll
    static void startTheProcess() {
        process = LoginProcess.start(PEPPER_BYTES, INSTITUTION, TEST_PASSWORD, Map.of(
                "confia.web.delay.max-wait", "1ms",
                "confia.web.rate-limit.admin-login.request-limit", "10000",
                "confia.web.rate-limit.admin-login.failure-threshold", "10000"));
        limit = limitFor(verificationCost());
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
                .as("median of the existing account: %s ms, median of the unknown identifier: %s ms, "
                        + "limit %s ms", millis(gate.first()), millis(gate.second()), millis(limit))
                .isLessThanOrEqualTo(limit);
    }

    /** BI56: an unknown identifier answered at once, which is what omitting the decoy amounts to. */
    @Test
    void theSameGateFailsWhenTheUnknownIdentifierIsAnsweredWithoutTheDecoyVerification() {
        seedAccount(EXISTING, false, false);

        Gate gate = measure(this::existingAccount, this::answeredAtOnce);

        assertThat(gate.difference())
                .as("the difference the gate measured: %s ms (median of the existing account %s ms, "
                        + "median of the immediate answer %s ms, limit %s ms)",
                        millis(gate.difference()), millis(gate.first()), millis(gate.second()),
                        millis(limit))
                .isGreaterThan(limit);
    }

    // ---------------------------------------------------------------------------------------------

    private record Gate(Duration first, Duration second) {

        Duration difference() {
            return first.minus(second).abs();
        }
    }

    /**
     * The specified 50 ms, narrowed to half the cost of one verification when that is smaller. A cost
     * below {@code MINIMUM_COST} means the calibration measured something other than an Argon2id
     * verification of the floor profile, and the gate refuses to run on it.
     */
    private static Duration limitFor(Duration verificationCost) {
        assertThat(verificationCost)
                .as("one decoy verification of the process cost %s ms, below the %s ms a floor-profile "
                        + "Argon2id verification takes; the calibration is not measuring it",
                        millis(verificationCost), millis(MINIMUM_COST))
                .isGreaterThanOrEqualTo(MINIMUM_COST);
        Duration half = verificationCost.dividedBy(2);
        return half.compareTo(LIMIT) < 0 ? half : LIMIT;
    }

    /**
     * The median cost of the process's own decoy verification of a wrong password, after a warm-up:
     * the bean and the hash the unknown-account branch uses, with the profile and pepper the process
     * was started with.
     */
    private static Duration verificationCost() {
        PasswordHasher hasher = process.context().getBean(PasswordHasher.class);
        StoredPasswordHash stored = hasher.decoyHash();
        PlainPassword wrong = PlainPassword.of(WRONG_PASSWORD);
        for (int i = 0; i < WARM_UP; i++) {
            hasher.matches(wrong, stored);
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < VERIFICATIONS; i++) {
            long start = System.nanoTime();
            boolean matched = hasher.matches(wrong, stored);
            times.add(System.nanoTime() - start);
            assertThat(matched).isFalse();
        }
        return median(times);
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

    /** A duration in milliseconds with one decimal, so a message never hides what decided it. */
    private static String millis(Duration duration) {
        return String.format(java.util.Locale.ROOT, "%.1f", duration.toNanos() / 1_000_000.0);
    }

    private static Duration median(List<Long> nanos) {
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return Duration.ofNanos((sorted.get(middle - 1) + sorted.get(middle)) / 2);
    }
}
